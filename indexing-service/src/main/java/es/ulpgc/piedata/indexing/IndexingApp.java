package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.http.BadRequestException;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/**
 * Indexing Service (#4): reads ingested books from the datalake and writes metadata + inverted
 * index into the datamart ({@code docs/DATAMART.md}).
 *
 * <p>Internal/administrative HTTP API (consumers: operators, scripts, benchmarks; the broker
 * consumer of piece D calls the same {@link IndexingService}):
 * <ul>
 *   <li>{@code POST /index/update/{book_id}}: index one book; 200 when it is committed (searchable)</li>
 *   <li>{@code POST /index/rebuild}: rebuild the datamart from every receipt, atomically</li>
 *   <li>{@code GET /index/status}: counts, last update, size</li>
 *   <li>{@code GET /status}: health (from ServiceServer)</li>
 * </ul>
 * Run with {@code java -jar indexing-service/target/indexing-service.jar}; override settings with
 * environment variables, e.g. {@code SERVER_PORT=7012 DATA_DIR=/data} for a second replica.
 */
public final class IndexingApp {

    private IndexingApp() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load("application.properties");
        IndexingService service = openService(config);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                service.close();
            } catch (SQLException ignored) {
                // closing at shutdown: nothing useful to do
            }
        }));
        start(config, service);
    }

    /** Opens the datamart under {@code data.dir}. */
    public static IndexingService openService(AppConfig config) {
        try {
            return new IndexingService(config.getPath("data.dir"),
                    config.getInt("datamart.cache-mb", Datamart.DEFAULT_CACHE_MB));
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open the datamart under " + config.get("data.dir"), e);
        }
    }

    /** Builds the server, registers the routes and starts listening on {@code server.port}. */
    public static Javalin start(AppConfig config, IndexingService service) {
        Javalin app = ServiceServer.create(config);
        app.post("/index/update/{book_id}", ctx -> update(ctx, service));
        app.post("/index/rebuild", ctx -> rebuild(ctx, service));
        app.get("/index/status", ctx -> status(ctx, service));
        return app.start(config.getInt("server.port"));
    }

    private static void update(Context ctx, IndexingService service) {
        IndexingService.UpdateResult r = service.update(bookId(ctx.pathParam("book_id")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("book_id", r.bookId());
        body.put("index", "updated");
        body.put("distinct_terms", r.distinctTerms());
        body.put("tokens", r.tokens());
        body.put("elapsed_ms", r.elapsedMs());
        ctx.result(Json.write(body));
    }

    private static void rebuild(Context ctx, IndexingService service) {
        IndexingService.RebuildResult r = service.rebuild();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("books_processed", r.booksProcessed());
        body.put("elapsed_time", String.format(Locale.ROOT, "%.1fs", r.elapsedMs() / 1000.0));
        body.put("elapsed_ms", r.elapsedMs());
        ctx.result(Json.write(body));
    }

    private static void status(Context ctx, IndexingService service) {
        IndexingService.Status s = service.status();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("books_indexed", s.booksIndexed());
        body.put("terms", s.terms());
        body.put("postings", s.postings());
        body.put("last_update", s.lastUpdate());
        body.put("index_size_MB", s.indexSizeMb());
        body.put("rebuild_in_progress", s.rebuildInProgress());
        ctx.result(Json.write(body));
    }

    /** A Gutenberg id: digits only, greater than zero. */
    static long bookId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0 && raw.chars().allMatch(c -> c >= '0' && c <= '9')) {
                return id;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new BadRequestException("invalid_book_id", "book_id must be a positive integer, got '" + raw + "'");
    }
}
