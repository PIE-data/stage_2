package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import io.javalin.Javalin;

/**
 * Indexing Service - builds the inverted index and metadata datamart (#4).
 *
 * <p>Skeleton: only {@code GET /status} for now. The real endpoints are added in issue #4.
 * Run with {@code java -jar indexing-service/target/indexing-service.jar}; override settings with environment variables,
 * e.g. {@code SERVER_PORT=7012} for a second replica.
 */
public final class IndexingApp {

    private IndexingApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    /** Builds the server, registers the routes and starts listening on {@code server.port}. */
    public static Javalin start(AppConfig config) {
        Javalin app = ServiceServer.create(config);
        // routes of issue #4 go here
        return app.start(config.getInt("server.port"));
    }
}
