package es.ulpgc.piedata.indexing;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.datalake.HashLayout;
import es.ulpgc.piedata.common.datamart.Datamart;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The HTTP API of the Indexing service, against a data folder holding the 20 golden books. */
class IndexingAppTest {

    @TempDir
    Path dataDir;

    private IndexingService service;
    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Properties sys = new Properties();
        sys.setProperty("server.port", "0");                     // any free port
        sys.setProperty("data.dir", dataDir.toString());
        AppConfig config = AppConfig.load("application.properties", Map.of(), sys);
        service = IndexingApp.openService(config);
        app = IndexingApp.start(config, service);
    }

    @AfterEach
    void stop() throws Exception {
        app.stop();
        service.close();
    }

    private HttpResponse<String> call(String method, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private String exportedSha256() throws Exception {
        try (Datamart d = Datamart.open(Datamart.defaultFile(dataDir))) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d.exportCanonical()));
        }
    }

    @Test
    void answersStatus() throws Exception {
        HttpResponse<String> r = call("GET", "/status");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("\"service\":\"indexing\""), r.body());
    }

    @Test
    void updateIndexesOneBookAndIsIdempotent() throws Exception {
        TestWorkspace.ingest(dataDir, 8708);

        HttpResponse<String> first = call("POST", "/index/update/8708");
        assertEquals(200, first.statusCode(), first.body());
        assertEquals("updated", json(first).get("index").getAsString());
        assertEquals(8708, json(first).get("book_id").getAsLong());

        JsonObject before = json(call("GET", "/index/status"));
        assertEquals(1, before.get("books_indexed").getAsLong());

        assertEquals(200, call("POST", "/index/update/8708").statusCode());   // same book again
        JsonObject after = json(call("GET", "/index/status"));
        assertEquals(before.get("books_indexed"), after.get("books_indexed"));
        assertEquals(before.get("terms"), after.get("terms"));
        assertEquals(before.get("postings"), after.get("postings"));
    }

    @Test
    void errorsAreDocumentedJson() throws Exception {
        HttpResponse<String> notIngested = call("POST", "/index/update/1342");
        assertEquals(404, notIngested.statusCode());
        assertEquals("not_ingested", json(notIngested).get("error").getAsString());

        for (String bad : List.of("abc", "0", "-5", "12a", "99999999999999999999")) {
            HttpResponse<String> r = call("POST", "/index/update/" + bad);
            assertEquals(400, r.statusCode(), bad);
            assertEquals("invalid_book_id", json(r).get("error").getAsString(), bad);
        }

        TestWorkspace.ingest(dataDir, 8708);
        Files.delete(dataDir.resolve(HashLayout.bodyPath(8708)));
        HttpResponse<String> missing = call("POST", "/index/update/8708");
        assertEquals(409, missing.statusCode());
        assertEquals("datalake_inconsistent", json(missing).get("error").getAsString());
    }

    @Test
    void rebuildIndexesEveryIngestedBookLikeStage1() throws Exception {
        List<Long> ids = TestWorkspace.ingestGolden(dataDir);

        HttpResponse<String> r = call("POST", "/index/rebuild");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(ids.size(), json(r).get("books_processed").getAsInt());
        assertTrue(json(r).get("elapsed_time").getAsString().endsWith("s"));

        JsonObject status = json(call("GET", "/index/status"));
        assertEquals(ids.size(), status.get("books_indexed").getAsLong());
        assertTrue(status.get("index_size_MB").getAsDouble() > 0);
        assertEquals(TestWorkspace.expectedCanonicalSha256(), exportedSha256());

        assertEquals(200, call("POST", "/index/rebuild").statusCode());     // rebuild again: same index
        assertEquals(TestWorkspace.expectedCanonicalSha256(), exportedSha256());
    }

    @Test
    void concurrentUpdatesBuildTheStage1Index() throws Exception {
        List<Long> ids = TestWorkspace.ingestGolden(dataDir);
        ExecutorService clients = Executors.newFixedThreadPool(8);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (long id : ids) {
                results.add(clients.submit(() -> call("POST", "/index/update/" + id).statusCode()));
                results.add(clients.submit(() -> call("POST", "/index/update/" + id).statusCode())); // duplicates
            }
            for (Future<Integer> f : results) {
                assertEquals(200, f.get());
            }
        } finally {
            clients.shutdownNow();
        }
        assertEquals(TestWorkspace.expectedCanonicalSha256(), exportedSha256());
    }
}
