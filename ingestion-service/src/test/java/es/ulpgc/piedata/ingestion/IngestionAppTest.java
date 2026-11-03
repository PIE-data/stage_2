package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.ingestion.storage.FailedBooksLog;
import io.javalin.Javalin;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionAppTest {

    private BrokerService broker;
    private String brokerUrl;
    private HttpClient client;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(tempDir.resolve("broker").toFile());
        broker.addConnector("tcp://127.0.0.1:0");
        broker.start();
        broker.waitUntilStarted();
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test
    void startsAndAnswersStatus(@TempDir Path dataDir) throws Exception {
        Properties props = new Properties();
        props.setProperty("server.port", "0");
        props.setProperty("data.dir", dataDir.toString());
        props.setProperty("broker.url", brokerUrl);

        Javalin app = IngestionApp.start(AppConfig.load("application.properties", Map.of(), props));
        try {
            HttpResponse<String> r = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"service\":\"ingestion\""));
            assertTrue(r.body().contains("\"ingested_count\":0"));
        } finally {
            app.stop();
        }
    }

    @Test
    void handlesIngestListAndStatusQueries(@TempDir Path dataDir) throws Exception {
        ReceiptStore receiptStore = new ReceiptStore(dataDir);
        receiptStore.write(new Receipt(1342L, "header/path", "body/path", "2026-11-02T12:00:00Z"));

        FailedBooksLog failedLog = new FailedBooksLog(dataDir);
        failedLog.record(9999L, "NOT_FOUND");

        Properties props = new Properties();
        props.setProperty("server.port", "0");
        props.setProperty("data.dir", dataDir.toString());
        props.setProperty("broker.url", brokerUrl);

        Javalin app = IngestionApp.start(AppConfig.load("application.properties", Map.of(), props));
        try {
            HttpResponse<String> listResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/list")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());
            assertTrue(listResp.body().contains("1342"));

            HttpResponse<String> downloadedResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/status/1342")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, downloadedResp.statusCode());
            assertTrue(downloadedResp.body().contains("\"status\":\"downloaded\""));

            HttpResponse<String> failedResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/status/9999")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, failedResp.statusCode());
            assertTrue(failedResp.body().contains("\"status\":\"failed\""));
            assertTrue(failedResp.body().contains("\"reason\":\"NOT_FOUND\""));

            HttpResponse<String> notFoundResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/status/555")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, notFoundResp.statusCode());
            assertTrue(notFoundResp.body().contains("\"status\":\"not_found\""));

            HttpResponse<String> invalidResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/status/abc")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidResp.statusCode());
        } finally {
            app.stop();
        }
    }

    @Test
    void postIngestReturns200IfAlreadyIngestedOr202IfQueued(@TempDir Path dataDir) throws Exception {
        ReceiptStore receiptStore = new ReceiptStore(dataDir);
        receiptStore.write(new Receipt(1342L, "header/path", "body/path", "2026-11-02T12:00:00Z"));

        Properties props = new Properties();
        props.setProperty("server.port", "0");
        props.setProperty("data.dir", dataDir.toString());
        props.setProperty("broker.url", brokerUrl);

        Javalin app = IngestionApp.start(AppConfig.load("application.properties", Map.of(), props));
        try {
            HttpResponse<String> alreadyResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/1342"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, alreadyResp.statusCode());
            assertTrue(alreadyResp.body().contains("\"status\":\"already_ingested\""));

            HttpResponse<String> acceptedResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/777"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, acceptedResp.statusCode());
            assertTrue(acceptedResp.body().contains("\"status\":\"accepted\""));

            HttpResponse<String> invalidResp = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/ingest/-10"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidResp.statusCode());
        } finally {
            app.stop();
        }
    }
}