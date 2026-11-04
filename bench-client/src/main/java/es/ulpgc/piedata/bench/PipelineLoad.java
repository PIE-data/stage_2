package es.ulpgc.piedata.bench;

import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BookIndexed;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Throughput and per-book latency of the message-driven pipeline (brief section 6.4).
 *
 * <ul>
 *   <li>{@code pipeline}: sends {@code POST /ingest/{id}} for each id (round-robin over the given
 *       ingestion replicas) and waits for the {@code BookIndexed} of each one: ingestion +
 *       indexing + coordination. The datalake must not contain the books yet (a book already
 *       ingested answers 200 and produces no event: it is counted as skipped).</li>
 *   <li>{@code index-only}: publishes the {@code BookDownloaded} message of books already in the
 *       datalake (receipts under {@code --data-dir}) exactly as ingestion would, and waits for
 *       their {@code BookIndexed}: indexing + coordination only, without the download.</li>
 * </ul>
 * The client subscribes to the {@code books.indexed} topic BEFORE sending anything. The latency
 * of a book is client send → client receipt of its {@code BookIndexed}; both times come from the
 * same clock, so no clock synchronisation between processes is needed.
 *
 * <p>Output: {@code summary.json}, {@code books.csv} (one row per book) and, with
 * {@code --broker-http}, {@code broker.csv} (queue statistics once per second).
 */
final class PipelineLoad {

    private PipelineLoad() {
    }

    /** Times of one book, ms since the start of the run; -1 = did not happen. */
    static final class Book {
        final long id;
        volatile long submittedMs = -1;
        volatile int status;
        volatile long indexedMs = -1;

        Book(long id) {
            this.id = id;
        }
    }

    static Map<String, Object> run(Args args, boolean indexOnly) throws Exception {
        String brokerUrl = args.get("broker", "tcp://127.0.0.1:61616");
        String brokerHttp = args.get("broker-http", null);
        String countArg = args.get("count", "all");     // first N ids, or all
        int count = countArg.equals("all") ? Integer.MAX_VALUE : Integer.parseInt(countArg);
        double rate = args.getDouble("rate", 0);              // submissions per second, 0 = unpaced
        int timeoutS = args.getInt("timeout", 600);
        List<String> ingestUrls = new ArrayList<>();
        Path dataDir = null;
        List<Long> ids;
        if (indexOnly) {
            dataDir = args.getPath("data-dir");
            ids = new ReceiptStore(dataDir).ids();
        } else {
            for (String u : args.get("ingest-url").split(",")) {
                ingestUrls.add(SearchLoad.trimSlash(u.strip()));
            }
            ids = readIds(args.getPath("ids"));
        }
        Output out = new Output(args.getPath("out"));
        Map<String, Object> summary = Output.head(indexOnly ? "index-only" : "pipeline", args);
        args.rejectUnknown();
        ids = ids.subList(0, Math.min(count, ids.size()));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("no book ids to send");
        }

        Map<Long, Book> books = new LinkedHashMap<>();
        for (long id : ids) {
            books.put(id, new Book(id));
        }
        Map<Long, Book> pending = new ConcurrentHashMap<>(books);

        long t0 = System.nanoTime();
        try (BrokerClient client = new BrokerClient(brokerUrl);
             BrokerPoller poller = brokerHttp == null ? null : new BrokerPoller(brokerHttp, t0)) {
            Connection conn = client.getConnection();
            Session listenSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer indexed = listenSession.createConsumer(listenSession.createTopic(Destinations.BOOKS_INDEXED));
            Thread listener = new Thread(() -> listen(indexed, books, pending, t0), "books-indexed-listener");
            listener.setDaemon(true);
            listener.start();
            if (poller != null) {
                poller.start();
            }

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            ReceiptStore receipts = indexOnly ? new ReceiptStore(dataDir) : null;
            Session sendSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            javax.jms.Queue downloaded = sendSession.createQueue(Destinations.BOOKS_DOWNLOADED);
            int i = 0;
            for (Book b : books.values()) {
                pace(t0, i, rate);
                b.submittedMs = ms(t0);
                if (indexOnly) {
                    Receipt r = receipts.read(b.id).orElseThrow();
                    EventPublisher.publishInSession(sendSession, downloaded,
                            BookDownloaded.of(r.bookId(), r.headerPath(), r.bodyPath(), r.ingestedAt()), r.bookId());
                    b.status = 0;
                } else {
                    b.status = post(http, ingestUrls.get(i % ingestUrls.size()) + "/ingest/" + b.id);
                    if (b.status != 202) {
                        pending.remove(b.id);        // 200 already ingested or an error: no event will come
                    }
                }
                i++;
            }
            long submitEndMs = ms(t0);

            long deadline = t0 + timeoutS * 1_000_000_000L;
            while (!pending.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            summary.putAll(results(books, submitEndMs, indexOnly));
            if (poller != null) {
                poller.close();
                out.csv("broker.csv", "t_ms,queue,depth,enqueued,dequeued,inflight,avg_time_ms", poller.rows());
                summary.put("broker", poller.summary());
                if (!indexOnly) {
                    summary.put("ingestion_throughput_books_s", ingestionThroughput(poller.rows()));
                }
            }
            indexed.close();
            listenSession.close();
            sendSession.close();
        }

        List<String> rows = new ArrayList<>();
        for (Book b : books.values()) {
            long lat = b.indexedMs >= 0 && b.submittedMs >= 0 ? b.indexedMs - b.submittedMs : -1;
            rows.add(b.id + "," + b.submittedMs + "," + b.status + "," + b.indexedMs + "," + lat);
        }
        out.csv("books.csv", "book_id,submitted_ms,http_status,indexed_ms,latency_ms", rows);
        out.json("summary.json", summary);
        return summary;
    }

    private static void listen(MessageConsumer consumer, Map<Long, Book> books, Map<Long, Book> pending, long t0) {
        while (true) {
            try {
                Message m = consumer.receive(500);
                if (m == null) {
                    continue;
                }
                long id = EventParser.parse(((TextMessage) m).getText(), BookIndexed.class, BookIndexed.EVENT_NAME).book_id();
                Book b = books.get(id);
                if (b != null && b.indexedMs < 0) {    // a re-sent BookIndexed keeps the first time
                    b.indexedMs = ms(t0);
                    pending.remove(id);
                }
            } catch (Exception e) {
                return;                                 // consumer closed at the end of the run
            }
        }
    }

    static Map<String, Object> results(Map<Long, Book> books, long submitEndMs, boolean indexOnly) {
        int accepted = 0;
        int skipped = 0;
        int errors = 0;
        long firstSubmit = Long.MAX_VALUE;
        long lastIndexed = -1;
        List<Double> lat = new ArrayList<>();
        for (Book b : books.values()) {
            if (indexOnly || b.status == 202) {
                accepted++;
            } else if (b.status == 200) {
                skipped++;
            } else {
                errors++;
            }
            firstSubmit = Math.min(firstSubmit, b.submittedMs);
            if (b.indexedMs >= 0) {
                lastIndexed = Math.max(lastIndexed, b.indexedMs);
                lat.add((double) (b.indexedMs - b.submittedMs));
            }
        }
        int indexed = lat.size();
        double spanS = lastIndexed > firstSubmit ? (lastIndexed - firstSubmit) / 1000.0 : Double.NaN;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("books", books.size());
        m.put("accepted", accepted);
        m.put("skipped_already_ingested", skipped);
        m.put("errors", errors);
        m.put("indexed", indexed);
        m.put("missing", accepted - indexed);
        m.put("submit_duration_s", Stats.r(submitEndMs / 1000.0 - firstSubmit / 1000.0));
        m.put("duration_s", Stats.r(spanS));
        m.put("throughput_books_s", Stats.r(indexed / spanS));
        m.put("time_per_book_ms", Stats.r(spanS * 1000 / indexed));
        m.put("latency_ms", Stats.of(lat.stream().mapToDouble(Double::doubleValue).toArray()).toMap());
        return m;
    }

    /**
     * Books stored by ingestion per second: {@code books.downloaded} messages enqueued during the
     * run, divided by the time from the first sample to the sample where the total stopped growing.
     */
    static Object ingestionThroughput(List<String> brokerRows) {
        long firstT = -1;
        long firstEnq = -1;
        long lastGrowthT = -1;
        long lastEnq = -1;
        for (String row : brokerRows) {
            String[] f = row.split(",");
            if (!f[1].equals(Destinations.BOOKS_DOWNLOADED)) {
                continue;
            }
            long t = Long.parseLong(f[0]);
            long enq = Long.parseLong(f[3]);
            if (firstT < 0) {
                firstT = t;
                firstEnq = enq;
                lastEnq = enq;
            }
            if (enq > lastEnq) {
                lastEnq = enq;
                lastGrowthT = t;
            }
        }
        if (lastGrowthT <= firstT) {
            return null;
        }
        return Stats.r((lastEnq - firstEnq) / ((lastGrowthT - firstT) / 1000.0));
    }

    private static int post(HttpClient http, String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /** Waits until submission {@code i} is due at {@code rate} per second (open loop). */
    private static void pace(long t0, int i, double rate) throws InterruptedException {
        if (rate <= 0) {
            return;
        }
        long due = t0 + (long) (i * 1e9 / rate);
        long wait = due - System.nanoTime();
        if (wait > 0) {
            Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** One Gutenberg id per line (e.g. the Stage 1 manifests); blank lines and # comments skipped. */
    static List<Long> readIds(Path file) throws java.io.IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(String::strip)
                .filter(s -> !s.isEmpty() && !s.startsWith("#")).map(Long::parseLong).toList();
    }
}
