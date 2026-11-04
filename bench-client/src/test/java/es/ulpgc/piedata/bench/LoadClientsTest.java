package es.ulpgc.piedata.bench;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import es.ulpgc.piedata.common.datalake.HashLayout;
import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BookIndexed;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The load clients against fake services: an HTTP server and an in-memory broker. */
class LoadClientsTest {

    @TempDir
    Path dir;

    private HttpServer http;
    private BrokerService broker;
    private String brokerUrl;

    @BeforeEach
    void setUp() throws Exception {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.start();
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(dir.resolve("broker").toFile());
        broker.addConnector("tcp://127.0.0.1:0");
        broker.start();
        broker.waitUntilStarted();
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        http.stop(0);
        broker.stop();
        broker.waitUntilStopped();
    }

    private String httpBase() {
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private static JsonObject summary(Path out) throws Exception {
        return JsonParser.parseString(Files.readString(out.resolve("summary.json"))).getAsJsonObject();
    }

    @Test
    void searchLoadMeasuresLatencyAndCountsErrors() throws Exception {
        http.createContext("/search", ex -> {
            String q = ex.getRequestURI().getQuery();
            if (q.contains("q=bad")) {
                reply(ex, 500, "{}");
            } else {
                reply(ex, 200, "{\"query\":\"x\",\"total\": " + (q.contains("q=love") ? 0 : 3) + ",\"results\":[]}");
            }
        });
        Path queries = dir.resolve("queries.txt");
        Files.writeString(queries, "# test\nlove\nbad\nwhite whale\n");
        Path out = dir.resolve("search");
        SearchLoad.run(new Args(new String[]{"--url", httpBase(), "--queries", queries.toString(),
                "--concurrency", "3", "--warmup", "0", "--duration", "2", "--out", out.toString(), "--label", "t"}, 0));

        JsonObject s = summary(out);
        long requests = s.get("requests").getAsLong();
        long errors = s.get("errors").getAsLong();
        assertTrue(requests > 10, s.toString());
        assertTrue(errors > 0 && errors < requests, "one query in three fails: " + s);
        assertEquals("t", s.get("label").getAsString());
        assertTrue(s.getAsJsonObject("latency_ms").get("p95").getAsDouble() > 0);
        long empty = s.get("empty_results").getAsLong();
        assertTrue(empty > 0 && empty < requests - errors, "one query in three has 0 results: " + s);
        assertEquals(3.0, s.getAsJsonObject("results_total").get("max").getAsDouble());
        assertEquals(requests + 1, Files.readAllLines(out.resolve("requests.csv")).size());
        assertEquals(2 + 1, Files.readAllLines(out.resolve("timeline.csv")).size());
    }

    /** Fake ingestion: 202 and, a moment later, the BookIndexed of that book; book 3 is "already ingested". */
    @Test
    void pipelineLoadWaitsForEveryBookIndexed() throws Exception {
        try (BrokerClient client = new BrokerClient(brokerUrl)) {
            EventPublisher publisher = new EventPublisher(client);
            http.createContext("/ingest/", ex -> {
                long id = Long.parseLong(ex.getRequestURI().getPath().substring("/ingest/".length()));
                if (id == 3) {
                    reply(ex, 200, "{\"status\":\"already_ingested\"}");
                    return;
                }
                reply(ex, 202, "{\"status\":\"accepted\"}");
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                        publisher.publish(Destinations.BOOKS_INDEXED, true, BookIndexed.of(id), id);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }).start();
            });
            Path ids = dir.resolve("ids.txt");
            Files.writeString(ids, "1\n2\n3\n4\n5\n6\n");
            Path out = dir.resolve("pipeline");
            PipelineLoad.run(new Args(new String[]{"--ingest-url", httpBase() + "," + httpBase(), "--ids", ids.toString(),
                    "--count", "5", "--broker", brokerUrl, "--timeout", "20", "--out", out.toString()}, 0), false);

            JsonObject s = summary(out);
            assertEquals(5, s.get("books").getAsInt());
            assertEquals(4, s.get("accepted").getAsInt());
            assertEquals(1, s.get("skipped_already_ingested").getAsInt());
            assertEquals(4, s.get("indexed").getAsInt());
            assertEquals(0, s.get("missing").getAsInt());
            assertTrue(s.getAsJsonObject("latency_ms").get("min").getAsDouble() >= 40, s.toString());
            List<String> rows = Files.readAllLines(out.resolve("books.csv"));
            assertEquals(6, rows.size());
            assertTrue(rows.get(3).startsWith("3,") && rows.get(3).endsWith(",-1"), rows.get(3));
        }
    }

    /** Fake indexing: consumes books.downloaded and answers with BookIndexed. */
    @Test
    void indexOnlyPublishesBookDownloadedFromReceipts() throws Exception {
        Path data = dir.resolve("data");
        ReceiptStore receipts = new ReceiptStore(data);
        for (long id : new long[]{11, 12, 13}) {
            receipts.write(new Receipt(id, HashLayout.headerPath(id), HashLayout.bodyPath(id), "2026-10-01T00:00:00Z"));
        }
        AtomicBoolean running = new AtomicBoolean(true);
        try (BrokerClient client = new BrokerClient(brokerUrl)) {
            Connection c = client.getConnection();
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer downloaded = s.createConsumer(s.createQueue(Destinations.BOOKS_DOWNLOADED));
            Thread indexer = new Thread(() -> {
                try {
                    while (running.get()) {
                        Message m = downloaded.receive(200);
                        if (m != null) {
                            BookDownloaded d = EventParser.parse(((TextMessage) m).getText(), BookDownloaded.class,
                                    BookDownloaded.EVENT_NAME);
                            assertEquals(HashLayout.bodyPath(d.book_id()), d.body_path());
                            EventPublisher.publishInSession(s, s.createTopic(Destinations.BOOKS_INDEXED),
                                    BookIndexed.of(d.book_id()), d.book_id());
                        }
                    }
                } catch (Exception ignored) {
                    // session closed at the end of the test
                }
            });
            indexer.start();

            Path out = dir.resolve("index-only");
            Map<String, Object> r = PipelineLoad.run(new Args(new String[]{"--data-dir", data.toString(),
                    "--broker", brokerUrl, "--rate", "20", "--timeout", "20", "--out", out.toString()}, 0), true);
            running.set(false);
            indexer.join(2000);
            s.close();

            JsonObject sum = summary(out);
            assertEquals(3, sum.get("indexed").getAsInt(), r.toString());
            assertEquals(0, sum.get("missing").getAsInt());
            assertEquals("index-only", sum.get("kind").getAsString());
            assertTrue(sum.get("submit_duration_s").getAsDouble() >= 0.09, "rate 20/s: 3 sends take ~0.1 s");
        }
    }

    @Test
    void aggregateGroupsRepeatedRunsByLabel() throws Exception {
        for (int i = 1; i <= 3; i++) {
            Path run = dir.resolve("runs").resolve("r" + i);
            Files.createDirectories(run);
            Files.writeString(run.resolve("summary.json"),
                    "{\"kind\":\"search\",\"label\":\"c4\",\"throughput_qps\":" + (i * 10) + ",\"latency_ms\":{\"p95\":2}}");
        }
        Path csv = dir.resolve("agg.csv");
        Aggregate.run(new Args(new String[]{"--runs", dir.resolve("runs").toString(), "--out", csv.toString()}, 0));
        List<String> rows = Files.readAllLines(csv);
        assertEquals("label,kind,runs,metric,mean,sd,min,max", rows.get(0));
        assertTrue(rows.contains("c4,search,3,throughput_qps,20.000,10.000,10.000,30.000"), rows.toString());
        assertTrue(rows.contains("c4,search,3,latency_ms.p95,2.000,0.000,2.000,2.000"), rows.toString());
    }

    @Test
    void rejectsUnknownOptions() {
        Args a = new Args(new String[]{"--url", "x", "--concurency", "4"}, 0);
        a.get("url");
        IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, a::rejectUnknown);
        assertTrue(e.getMessage().contains("concurency"));
    }
}
