package es.ulpgc.piedata.feeder;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import io.javalin.Javalin;

/**
 * Feeder - decides which books to ingest and publishes ingestion requests (#2).
 *
 * <p>Skeleton: only {@code GET /status} for now. The real endpoints are added in issue #2.
 * Run with {@code java -jar feeder/target/feeder.jar}; override settings with environment variables,
 * e.g. {@code SERVER_PORT=7014} for a second replica.
 */
public final class FeederApp {

    private FeederApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    /** Builds the server, registers the routes and starts listening on {@code server.port}. */
    public static Javalin start(AppConfig config) {
        Javalin app = ServiceServer.create(config);
        // routes of issue #2 go here
        return app.start(config.getInt("server.port"));
    }
}
