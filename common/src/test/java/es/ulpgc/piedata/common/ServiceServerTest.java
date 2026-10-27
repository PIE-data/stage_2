package es.ulpgc.piedata.common;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ApiException;
import es.ulpgc.piedata.common.http.BadRequestException;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import io.javalin.http.NotFoundResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ServiceServerTest {

    private Javalin app;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        AppConfig config = AppConfig.of(Map.of(
                "service.name", "test-service",
                "server.threads.min", "2",
                "server.threads.max", "8"));
        app = ServiceServer.create(config);
        app.get("/boom", ctx -> { throw new IllegalStateException("boom"); });
        app.get("/missing", ctx -> { throw new NotFoundResponse("book 42 not found"); });
        app.get("/busy", ctx -> { throw new ApiException(503, "datamart_busy", "retry later"); });
        app.get("/bad", ctx -> { throw new BadRequestException("invalid_parameter", "limit must be 1-100"); });
        app.start(0);
    }

    @AfterEach
    void stop() {
        app.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void statusReportsServiceAndThreads() throws Exception {
        HttpResponse<String> r = get("/status");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        Map<String, Object> body = Json.read(r.body(), Map.class);
        assertEquals("test-service", body.get("service"));
        assertEquals("running", body.get("status"));
        assertEquals(8.0, body.get("threads_max"));
    }

    @Test
    void unknownRouteIsJson404() throws Exception {
        HttpResponse<String> r = get("/does-not-exist");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("\"error\":\"not_found\""), r.body());
    }

    @Test
    void notFoundThrownByHandlerIsJson404NotA500() throws Exception {
        HttpResponse<String> r = get("/missing");
        assertEquals(404, r.statusCode());
        assertEquals("{\"error\":\"not_found\",\"message\":\"book 42 not found\"}", r.body());
    }

    @Test
    void apiExceptionKeepsItsStatusAndCode() throws Exception {
        HttpResponse<String> r = get("/busy");
        assertEquals(503, r.statusCode());
        assertEquals("{\"error\":\"datamart_busy\",\"message\":\"retry later\"}", r.body());
    }

    @Test
    void badRequestIsJson400() throws Exception {
        HttpResponse<String> r = get("/bad");
        assertEquals(400, r.statusCode());
        assertEquals("{\"error\":\"invalid_parameter\",\"message\":\"limit must be 1-100\"}", r.body());
    }

    @Test
    void unexpectedErrorIsJson500WithoutDetails() throws Exception {
        HttpResponse<String> r = get("/boom");
        assertEquals(500, r.statusCode());
        assertTrue(r.body().contains("\"error\":\"internal_error\""), r.body());
        assertFalse(r.body().contains("boom"), "internal details must not leak to clients");
    }
}
