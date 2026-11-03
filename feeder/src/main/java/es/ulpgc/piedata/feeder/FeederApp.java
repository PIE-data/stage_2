package es.ulpgc.piedata.feeder;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public final class FeederApp {

    private static final Logger LOG = LoggerFactory.getLogger(FeederApp.class);

    private FeederApp() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load("application.properties");

        String cliManifest = null;
        Integer cliCount = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--manifest=")) {
                cliManifest = args[i].substring("--manifest=".length());
            } else if (args[i].equals("--manifest") && i + 1 < args.length) {
                cliManifest = args[++i];
            } else if (args[i].startsWith("--count=")) {
                cliCount = Integer.parseInt(args[i].substring("--count=".length()));
            } else if (args[i].equals("--count") && i + 1 < args.length) {
                cliCount = Integer.parseInt(args[++i]);
            }
        }

        if (cliManifest != null) {
            LOG.info("Running Feeder in CLI mode with manifest={} count={}", cliManifest, cliCount);
            try (FeederService service = new FeederService(config)) {
                Map<String, Object> result = service.run(cliManifest, cliCount);
                System.out.println(Json.write(result));
            }
            return;
        }

        start(config);
    }

    public static Javalin start(AppConfig config) {
        FeederService feederService = new FeederService(config);

        Runtime.getRuntime().addShutdownHook(new Thread(feederService::close));

        Javalin app = ServiceServer.create(config, c -> {}, feederService::status);
        app.events(event -> event.serverStopped(feederService::close));

        app.post("/feeder/run", ctx -> {
            String manifest = ctx.queryParam("manifest");
            String countParam = ctx.queryParam("count");
            Integer count = countParam != null ? Integer.parseInt(countParam) : null;
            Map<String, Object> result = feederService.run(manifest, count);
            ctx.result(Json.write(result));
        });

        app.get("/feeder/status", ctx -> ctx.result(Json.write(feederService.status())));

        return app.start(config.getInt("server.port"));
    }
}