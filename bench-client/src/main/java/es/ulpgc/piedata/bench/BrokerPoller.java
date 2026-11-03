package es.ulpgc.piedata.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Samples {@code GET /broker/stats} of the broker process once per second (queue depth,
 * enqueued/dequeued totals, average time in queue): the coordination cost of the pipeline.
 * {@code average_time_ms} is ActiveMQ's average since the broker started, so restart the broker
 * before every run.
 */
final class BrokerPoller implements AutoCloseable {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI uri;
    private final long t0Nanos;
    private final List<String> rows = Collections.synchronizedList(new ArrayList<>());
    private volatile JsonObject first;
    private volatile JsonObject last;
    private volatile boolean running = true;
    private final Thread thread;

    BrokerPoller(String brokerHttp, long t0Nanos) {
        this.uri = URI.create(SearchLoad.trimSlash(brokerHttp) + "/broker/stats");
        this.t0Nanos = t0Nanos;
        this.thread = new Thread(this::loop, "broker-poller");
        this.thread.setDaemon(true);
    }

    void start() {
        sample();                  // snapshot before the first request
        thread.start();
    }

    private void loop() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
            sample();
        }
    }

    private void sample() {
        try {
            String body = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            JsonObject stats = JsonParser.parseString(body).getAsJsonObject();
            long t = (System.nanoTime() - t0Nanos) / 1_000_000;
            for (Map.Entry<String, JsonElement> q : stats.entrySet()) {
                JsonObject s = q.getValue().getAsJsonObject();
                rows.add(t + "," + q.getKey() + "," + s.get("depth") + "," + s.get("enqueued") + ","
                        + s.get("dequeued") + "," + s.get("inflight") + "," + s.get("average_time_ms"));
            }
            if (first == null) {
                first = stats;
            }
            last = stats;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // a missed sample is not fatal: the CSV simply has a gap
        }
    }

    List<String> rows() {
        return new ArrayList<>(rows);
    }

    /** Per queue: messages enqueued/dequeued during the run, depth and average time in queue at the end. */
    Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (first == null || last == null) {
            m.put("error", "broker stats not available at " + uri);
            return m;
        }
        for (String q : last.keySet()) {
            JsonObject a = first.has(q) ? first.getAsJsonObject(q) : null;
            JsonObject b = last.getAsJsonObject(q);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("enqueued_during_run", b.get("enqueued").getAsLong() - (a == null ? 0 : a.get("enqueued").getAsLong()));
            s.put("dequeued_during_run", b.get("dequeued").getAsLong() - (a == null ? 0 : a.get("dequeued").getAsLong()));
            s.put("depth_at_end", b.get("depth").getAsLong());
            s.put("average_time_in_queue_ms", b.get("average_time_ms").getAsDouble());
            m.put(q, s);
        }
        return m;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sample();                  // snapshot after the last book
    }
}
