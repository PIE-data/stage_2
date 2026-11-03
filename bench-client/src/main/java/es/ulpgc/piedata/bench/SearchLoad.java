package es.ulpgc.piedata.bench;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Query response time under concurrent load (brief section 6.4): {@code concurrency} clients,
 * each sending {@code GET /search} requests one after the other (closed loop, no think time) for
 * {@code duration} seconds after {@code warmup} seconds that are not measured.
 *
 * <p>Output: {@code summary.json} (throughput, latency mean/sd/percentiles, errors),
 * {@code requests.csv} (every measured request) and {@code timeline.csv} (per second).
 */
final class SearchLoad {

    /** One measured request. */
    record Sample(long startMs, double latencyMs, int status) {
    }

    private SearchLoad() {
    }

    static Map<String, Object> run(Args args) throws Exception {
        String base = trimSlash(args.get("url"));
        List<String> queries = readQueries(args.getPath("queries"));
        int concurrency = args.getInt("concurrency", 1);
        int warmupS = args.getInt("warmup", 10);
        int durationS = args.getInt("duration", 60);
        int limit = args.getInt("limit", 10);
        Output out = new Output(args.getPath("out"));
        Map<String, Object> summary = Output.head("search", args);
        args.rejectUnknown();
        if (concurrency < 1 || durationS < 1 || warmupS < 0) {
            throw new IllegalArgumentException("concurrency and duration must be >= 1, warmup >= 0");
        }

        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        List<URI> uris = new ArrayList<>();
        for (String q : queries) {
            uris.add(URI.create(base + "/search?" + queryString(q, limit)));
        }

        ConcurrentLinkedQueue<Sample> samples = new ConcurrentLinkedQueue<>();
        long t0 = System.nanoTime();
        long measureFrom = t0 + warmupS * 1_000_000_000L;
        long end = measureFrom + durationS * 1_000_000_000L;
        CountDownLatch done = new CountDownLatch(concurrency);
        for (int c = 0; c < concurrency; c++) {
            int first = c;   // client c starts at query c: clients do not send the same query in lockstep
            Thread t = new Thread(() -> {
                try {
                    int i = first;
                    while (System.nanoTime() < end) {
                        URI uri = uris.get(i++ % uris.size());
                        long s = System.nanoTime();
                        int status = send(http, uri);
                        long e = System.nanoTime();
                        if (s >= measureFrom && e <= end) {
                            samples.add(new Sample((s - measureFrom) / 1_000_000, (e - s) / 1e6, status));
                        }
                    }
                } finally {
                    done.countDown();
                }
            }, "search-client-" + c);
            t.start();
        }
        done.await();

        List<Sample> list = new ArrayList<>(samples);
        list.sort((a, b) -> Long.compare(a.startMs(), b.startMs()));
        double[] ok = list.stream().filter(s -> s.status() == 200).mapToDouble(Sample::latencyMs).toArray();
        long errors = list.size() - ok.length;

        summary.put("queries", queries.size());
        summary.put("concurrency", concurrency);
        summary.put("measured_s", durationS);
        summary.put("requests", list.size());
        summary.put("errors", errors);
        summary.put("throughput_qps", Stats.r(ok.length / (double) durationS));
        summary.put("latency_ms", Stats.of(ok).toMap());

        List<String> rows = new ArrayList<>();
        for (Sample s : list) {
            rows.add(s.startMs() + "," + String.format(java.util.Locale.ROOT, "%.3f", s.latencyMs()) + "," + s.status());
        }
        out.csv("requests.csv", "start_ms,latency_ms,status", rows);
        out.csv("timeline.csv", "second,requests,errors,p95_ms", timeline(list, durationS));
        out.json("summary.json", summary);
        return summary;
    }

    /** -1 for a network error or timeout (counted as an error). */
    private static int send(HttpClient http, URI uri) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException e) {
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    static List<String> timeline(List<Sample> samples, int durationS) {
        List<List<Sample>> perSecond = new ArrayList<>();
        for (int i = 0; i < durationS; i++) {
            perSecond.add(new ArrayList<>());
        }
        for (Sample s : samples) {
            int sec = (int) Math.min(durationS - 1, s.startMs() / 1000);
            perSecond.get(sec).add(s);
        }
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < durationS; i++) {
            List<Sample> l = perSecond.get(i);
            double[] ok = l.stream().filter(s -> s.status() == 200).mapToDouble(Sample::latencyMs).toArray();
            Object p95 = ok.length == 0 ? "" : Stats.r(Stats.of(ok).p95());
            rows.add(i + "," + l.size() + "," + (l.size() - ok.length) + "," + p95);
        }
        return rows;
    }

    /**
     * One query per line; blank lines and lines starting with {@code #} are skipped. A line with
     * {@code =} is a ready query string ({@code q=love&author=austen}), otherwise it is the text of q.
     */
    static List<String> readQueries(Path file) throws IOException {
        List<String> q = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        if (q.isEmpty()) {
            throw new IllegalArgumentException("no queries in " + file);
        }
        return q;
    }

    static String queryString(String line, int limit) {
        String qs = line.contains("=") ? line : "q=" + URLEncoder.encode(line, StandardCharsets.UTF_8);
        return qs.contains("limit=") ? qs : qs + "&limit=" + limit;
    }

    static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
