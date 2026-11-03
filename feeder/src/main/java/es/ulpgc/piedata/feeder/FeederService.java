package es.ulpgc.piedata.feeder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.common.events.BookIndexed;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import es.ulpgc.piedata.common.events.IngestRequested;
import es.ulpgc.piedata.common.events.TransactedConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class FeederService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(FeederService.class);

    private final AppConfig config;
    private final BrokerClient brokerClient;
    private final EventPublisher publisher;
    private final TransactedConsumer indexedTopicConsumer;
    private final ReceiptStore receiptStore;
    private final HttpClient httpClient;

    private final AtomicInteger requestedCount = new AtomicInteger(0);
    private final Set<Long> requestedBookIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> indexedBookIds = ConcurrentHashMap.newKeySet();

    public FeederService(AppConfig config) {
        this.config = config;
        this.brokerClient = new BrokerClient(config.get("broker.url"));
        this.publisher = new EventPublisher(brokerClient);
        this.receiptStore = new ReceiptStore(config.getPath("data.dir"));
        this.httpClient = HttpClient.newHttpClient();

        this.indexedTopicConsumer = new TransactedConsumer(
                brokerClient,
                Destinations.BOOKS_INDEXED,
                true,
                1,
                (session, textMsg) -> {
                    BookIndexed event = EventParser.parse(textMsg.getText(), BookIndexed.class, BookIndexed.EVENT_NAME);
                    indexedBookIds.add(event.book_id());
                    LOG.info("Feeder observed book_id={} indexed (total indexed: {})", event.book_id(), indexedBookIds.size());
                }
        );
        this.indexedTopicConsumer.start();
    }

    public Map<String, Object> run(String manifestInput, Integer countLimit) throws Exception {
        Path manifestPath = resolveManifestPath(manifestInput);
        if (!Files.exists(manifestPath)) {
            throw new IllegalArgumentException("Manifest file not found: " + manifestPath);
        }

        List<Long> allManifestIds = readManifest(manifestPath);
        Set<Long> alreadyIngested = getAlreadyIngestedIds();

        List<Long> toRequest = new ArrayList<>();
        for (Long id : allManifestIds) {
            if (!alreadyIngested.contains(id)) {
                toRequest.add(id);
                if (countLimit != null && toRequest.size() >= countLimit) {
                    break;
                }
            }
        }

        for (Long id : toRequest) {
            publisher.publish(Destinations.INGEST_REQUESTS, false, IngestRequested.of(id), id);
            requestedBookIds.add(id);
            requestedCount.incrementAndGet();
        }

        LOG.info("Feeder queued {} books from manifest {} (skipped {} already ingested)",
                toRequest.size(), manifestPath, allManifestIds.size() - toRequest.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("manifest", manifestPath.toString());
        result.put("manifest_total", allManifestIds.size());
        result.put("already_ingested", allManifestIds.size() - toRequest.size());
        result.put("requested", toRequest.size());
        return result;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("requested", requestedCount.get());
        status.put("downloaded", countDownloaded());
        status.put("indexed", indexedBookIds.size());
        status.put("failed", countFailed());
        return status;
    }

    private int countDownloaded() {
        try {
            Set<Long> ingested = getAlreadyIngestedIds();
            int downloaded = 0;
            for (Long id : requestedBookIds) {
                if (ingested.contains(id)) {
                    downloaded++;
                }
            }
            return downloaded;
        } catch (Exception e) {
            return receiptStore.ids().size();
        }
    }

    private int countFailed() {
        Path failedFile = config.getPath("data.dir").resolve("control").resolve("failed_books.txt");
        if (!Files.exists(failedFile)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(failedFile)) {
            return (int) lines.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private Set<Long> getAlreadyIngestedIds() {
        String ingestionUrl = config.get("ingestion.url", "http://localhost:7001");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ingestionUrl + "/ingest/list"))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Set<Long> ids = new HashSet<>();
                JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();
                for (JsonElement el : arr) {
                    ids.add(el.getAsLong());
                }
                return ids;
            }
        } catch (Exception ignored) {
        }
        return new HashSet<>(receiptStore.ids());
    }

    private Path resolveManifestPath(String manifestInput) {
        if (manifestInput == null || manifestInput.isBlank()) {
            return Path.of("spec/corpus/manifest_100.txt");
        }
        if (manifestInput.matches("\\d+")) {
            return Path.of("spec/corpus/manifest_" + manifestInput + ".txt");
        }
        return Path.of(manifestInput);
    }

    private List<Long> readManifest(Path path) throws IOException {
        try (Stream<String> lines = Files.lines(path)) {
            return lines.map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .map(Long::parseLong)
                    .toList();
        }
    }

    @Override
    public void close() {
        indexedTopicConsumer.close();
        try {
            brokerClient.close();
        } catch (Exception ignored) {
        }
    }
}