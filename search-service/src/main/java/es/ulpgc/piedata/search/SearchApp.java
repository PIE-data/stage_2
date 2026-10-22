package es.ulpgc.piedata.search;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import io.javalin.Javalin;

/**
 * Search Service - front-end API over the published datamart (#5).
 *
 * <p>Skeleton: only {@code GET /status} for now. The real endpoints are added in issue #5.
 * Run with {@code java -jar search-service/target/search-service.jar}; override settings with environment variables,
 * e.g. {@code SERVER_PORT=7013} for a second replica.
 */
public final class SearchApp {

    private SearchApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    /** Builds the server, registers the routes and starts listening on {@code server.port}. */
    public static Javalin start(AppConfig config) {
        Javalin app = ServiceServer.create(config);
        // routes of issue #5 go here
        return app.start(config.getInt("server.port"));
    }
}
