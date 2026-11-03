package es.ulpgc.piedata.broker;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class BrokerApp {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerApp.class);

    private BrokerApp() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load("application.properties");
        start(config);
    }

    public static Javalin start(AppConfig config) {
        int brokerPort = config.getInt("broker.port");
        String dataDir = config.get("broker.data-dir");

        try {
            EmbeddedBroker broker = new EmbeddedBroker(brokerPort, dataDir);
            broker.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    broker.close();
                } catch (Exception e) {
                    LOG.error("Error shutting down broker", e);
                }
            }));

            Javalin app = ServiceServer.create(config, c -> {}, () -> Map.of(
                    "broker_port", brokerPort,
                    "queues", broker.getAllQueueStats()
            ));

            app.get("/broker/stats", ctx -> ctx.result(Json.write(broker.getAllQueueStats())));

            return app.start(config.getInt("server.port"));
        } catch (Exception e) {
            LOG.error("Failed to start embedded ActiveMQ broker", e);
            throw new RuntimeException("Could not start broker", e);
        }
    }
}