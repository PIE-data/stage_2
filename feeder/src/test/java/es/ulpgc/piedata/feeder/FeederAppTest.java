package es.ulpgc.piedata.feeder;

import es.ulpgc.piedata.common.config.AppConfig;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeederAppTest {

    @Test
    void startsWithItsOwnDefaultsAndAnswersStatus() throws Exception {
        Properties sys = new Properties();
        sys.setProperty("server.port", "0"); // any free port
        Javalin app = FeederApp.start(AppConfig.load("application.properties", Map.of(), sys));
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + "/status")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"service\":\"feeder\""), r.body());
        } finally {
            app.stop();
        }
    }
}
