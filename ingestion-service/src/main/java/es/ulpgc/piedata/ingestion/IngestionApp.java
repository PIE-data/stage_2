package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import io.javalin.Javalin;

/**
 * Ingestion Service - downloads books into the datalake (#3).
 *
 * <p>Skeleton: only {@code GET /status} for now. The real endpoints are added in issue #3.
 * Run with {@code java -jar ingestion-service/target/ingestion-service.jar}; override settings with environment variables,
 * e.g. {@code SERVER_PORT=7011} for a second replica.
 */
public final class IngestionApp {

    private IngestionApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    /** Builds the server, registers the routes and starts listening on {@code server.port}. */
    public static Javalin start(AppConfig config) {
        Javalin app = ServiceServer.create(config);
        // routes of issue #3 go here
        return app.start(config.getInt("server.port"));
    }
}
