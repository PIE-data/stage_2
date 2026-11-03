package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventPublisher;
import es.ulpgc.piedata.common.events.IngestRequested;
import es.ulpgc.piedata.common.http.ApiException;
import es.ulpgc.piedata.common.http.BadRequestException;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;

public final class IngestionApp {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionApp.class);

    private IngestionApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    public static Javalin start(AppConfig config) {
        Path dataDir = Path.of(config.get("data.dir", "./data"));
        String sourceBaseUrl = config.get("source.base-url", "https://www.gutenberg.org");
        IngestionService ingestionService = new IngestionService(dataDir, sourceBaseUrl);

        String brokerUrl = config.get("broker.url", "tcp://localhost:61616");
        int consumerThreads = config.getInt("consumer.threads", 4);

        BrokerClient brokerClient = new BrokerClient(brokerUrl);
        brokerClient.getFactory().setSendTimeout(3000);
        EventPublisher publisher = new EventPublisher(brokerClient);
        IngestConsumer consumer = new IngestConsumer(brokerClient, ingestionService, consumerThreads);
        consumer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> closeResources(consumer, brokerClient)));

        Javalin app = ServiceServer.create(config, c -> {}, () -> Map.of(
                "ingested_count", ingestionService.listIngestedIds().size()
        ));

        app.events(event -> event.serverStopped(() -> closeResources(consumer, brokerClient)));

        app.post("/ingest/{book_id}", ctx -> {
            long bookId = parseBookId(ctx.pathParam("book_id"));
            if (ingestionService.getReceipt(bookId).isPresent()) {
                ctx.status(200).result(Json.write(Map.of("status", "already_ingested", "book_id", bookId)));
                return;
            }
            try {
                publisher.publish(Destinations.INGEST_REQUESTS, false, IngestRequested.of(bookId), bookId);
                ctx.status(202).result(Json.write(Map.of("status", "accepted", "book_id", bookId)));
            } catch (Exception e) {
                LOG.error("Failed to publish IngestRequested for book_id={}", bookId, e);
                throw new ApiException(503, "broker_unavailable", "Cannot publish to message broker: " + e.getMessage());
            }
        });

        app.get("/ingest/status/{book_id}", ctx -> {
            long bookId = parseBookId(ctx.pathParam("book_id"));
            IngestionService.BookStatus status = ingestionService.status(bookId);
            ctx.status(200).result(Json.write(status));
        });

        app.get("/ingest/list", ctx -> {
            ctx.status(200).result(Json.write(ingestionService.listIngestedIds()));
        });

        return app.start(config.getInt("server.port"));
    }

    private static long parseBookId(String param) {
        try {
            long id = Long.parseLong(param);
            if (id <= 0) {
                throw new BadRequestException("invalid_book_id", "book_id must be a positive integer");
            }
            return id;
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid_book_id", "book_id must be a positive integer");
        }
    }

    private static void closeResources(IngestConsumer consumer, BrokerClient brokerClient) {
        try {
            consumer.close();
        } catch (Exception ignored) {
        }
        try {
            brokerClient.close();
        } catch (Exception ignored) {
        }
    }
}