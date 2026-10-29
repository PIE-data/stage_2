package es.ulpgc.piedata.search;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.http.ApiException;
import es.ulpgc.piedata.common.http.BadRequestException;
import es.ulpgc.piedata.common.http.ServiceServer;
import es.ulpgc.piedata.common.json.Json;
import io.javalin.Javalin;
import io.javalin.http.Context;

public final class SearchApp {

    private SearchApp() {
    }

    public static void main(String[] args) {
        start(AppConfig.load("application.properties"));
    }

    public static Javalin start(AppConfig config) {
        SearchService service = new SearchService(config.getPath("data.dir"));
        SearchCatalog catalog = new SearchCatalog(config.getPath("data.dir"));
        Javalin app = ServiceServer.create(
                config,
                configuration -> configuration.bundledPlugins.enableCors(
                        cors -> cors.addRule(rule -> rule.anyHost())
                ),
                catalog::status
        );

        app.before(ctx ->
                ctx.contentType("application/json; charset=utf-8")
        );

        app.get("/search", ctx -> {
            SearchRequest request = SearchRequest.parse(
                    parameter(ctx, "q"),
                    parameter(ctx, "author"),
                    parameter(ctx, "language"),
                    parameter(ctx, "year"),
                    parameter(ctx, "offset"),
                    parameter(ctx, "limit")
            );

            ctx.result(Json.write(service.search(request)));
        });

        app.get("/search/filters", ctx ->
                ctx.result(Json.write(catalog.filters()))
        );

        app.get("/books/{book_id}", ctx -> {
            long id = bookId(ctx.pathParam("book_id"));

            SearchCatalog.BookDetails book = catalog.book(id)
                    .orElseThrow(() -> new ApiException(
                            404,
                            "not_found",
                            "book " + id + " was not found"
                    ));

            ctx.result(Json.write(book));
        });

        return app.start(config.getInt("server.port"));
    }

    private static String parameter(Context ctx, String name) {
        if (ctx.queryParams(name).size() > 1) {
            throw new BadRequestException(
                    "invalid_parameter",
                    name + " must be specified only once"
            );
        }
        return ctx.queryParam(name);
    }

    private static long bookId(String raw) {
        if (raw.matches("[0-9]+")) {
            try {
                long id = Long.parseLong(raw);
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignored) {
                // Out-of-range identifiers are invalid parameters.
            }
        }

        throw new BadRequestException(
                "invalid_book_id",
                "book_id must be a positive integer"
        );
    }
}