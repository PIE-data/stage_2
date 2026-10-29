package es.ulpgc.piedata.common.http;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.config.JavalinConfig;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Builds the HTTP server of a service with the conventions shared by all services:
 * <ul>
 *   <li>JSON responses ({@code application/json});</li>
 *   <li>request thread pool sized from {@code server.threads.min} / {@code server.threads.max};</li>
 *   <li>{@code GET /status} health endpoint;</li>
 *   <li>errors always returned as {@link ApiError}: the chosen status for {@link ApiException}
 *       (400 for {@link BadRequestException}), 404 for unknown routes, 500 for anything
 *       unexpected.</li>
 * </ul>
 * Each service adds its own routes to the returned {@link Javalin} before calling {@code start}.
 */
public final class ServiceServer {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceServer.class);

    private ServiceServer() {
    }

    @FunctionalInterface
    public interface StatusDetails {
        Map<String, Object> read() throws Exception;
    }

    public static Javalin create(AppConfig config) {
        return create(config, configuration -> {}, Map::of);
    }

    public static Javalin create(
            AppConfig config,
            Consumer<JavalinConfig> configure,
            StatusDetails statusDetails
    ) {
        String service = config.get("service.name");
        int minThreads = config.getInt("server.threads.min");
        int maxThreads = config.getInt("server.threads.max");
        Instant startedAt = Instant.now();

        Javalin app = Javalin.create(c -> {
            c.http.defaultContentType = "application/json";
            c.showJavalinBanner = false;
            c.jetty.threadPool = new QueuedThreadPool(maxThreads, minThreads);
            configure.accept(c);
        });

        app.get("/status", ctx -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("service", service);
            body.put("status", "running");
            body.put("port", app.port());
            body.put("threads_min", minThreads);
            body.put("threads_max", maxThreads);
            body.put("uptime_s", Duration.between(startedAt, Instant.now()).toSeconds());
            body.putAll(statusDetails.read());
            ctx.result(Json.write(body));
        });

        app.exception(ApiException.class, (e, ctx) ->
                ctx.status(e.status()).result(Json.write(new ApiError(e.code(), e.getMessage()))));
        // Javalin's own responses (unknown route, NotFoundResponse thrown by a handler, ...).
        // Mapped explicitly, otherwise the generic handler below would turn them into 500.
        app.exception(HttpResponseException.class, (e, ctx) ->
                ctx.status(e.getStatus()).result(Json.write(new ApiError(codeFor(e.getStatus()), e.getMessage()))));
        app.exception(Exception.class, (e, ctx) -> {
            LOG.error("unexpected error on {} {}", ctx.method(), ctx.path(), e);
            ctx.status(500).result(Json.write(new ApiError("internal_error", "unexpected server error")));
        });
        // Safety net: any 404 whose body is not already an ApiError gets one.
        app.error(404, ctx -> {
            String body = ctx.result();
            if (body == null || !body.startsWith("{\"error\"")) {
                ctx.result(Json.write(new ApiError("not_found", "no such resource: " + ctx.path())));
            }
        });

        LOG.info("{} configured: {}", service, config.asMap());
        return app;
    }

    static String codeFor(int status) {
        return switch (status) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            default -> "http_" + status;
        };
    }
}
