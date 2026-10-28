package es.ulpgc.piedata.search;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.config.AppConfig;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class SearchHttpTest {

    @TempDir
    Path dataDir;

    private Javalin app;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() {
        Properties overrides = new Properties();
        overrides.setProperty("server.port", "0");
        overrides.setProperty("data.dir", dataDir.toString());

        app = SearchApp.start(
                AppConfig.load("application.properties", Map.of(), overrides)
        );
    }

    @AfterEach
    void stopServer() {
        if (app != null) {
            app.stop();
        }
    }

    @Test
    void missingDatabaseReturnsAnEmptyPage() throws Exception {
        var response = get("/search?q=sea");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("sea", body.get("query").getAsString());
        assertEquals(0, body.get("total").getAsInt());
        assertEquals(0, body.get("offset").getAsInt());
        assertEquals(20, body.get("limit").getAsInt());
        assertTrue(body.getAsJsonArray("results").isEmpty());
        assertFalse(Files.exists(dataDir.resolve("datamarts")));
        assertTrue(
                response.headers().firstValue("Content-Type")
                        .orElse("").startsWith("application/json")
        );
    }

    @Test
    void missingQueryReturnsStructuredBadRequest() throws Exception {
        var response = get("/search");
        JsonObject body = json(response);

        assertEquals(400, response.statusCode());
        assertEquals("invalid_parameter", body.get("error").getAsString());
        assertTrue(body.get("message").getAsString().contains("q"));
    }

    @Test
    void validatesParametersEvenWithoutDatabase() throws Exception {
        for (String parameters : new String[]{
                "limit=0",
                "limit=101",
                "offset=-1",
                "year=98",
                "language=english"
        }) {
            var response = get("/search?q=sea&" + parameters);

            assertEquals(400, response.statusCode(), parameters);
            assertTrue(json(response).has("error"));
            assertTrue(json(response).has("message"));
        }
    }

    @Test
    void rejectsRepeatedParameters() throws Exception {
        var response = get("/search?q=sea&limit=10&limit=20");

        assertEquals(400, response.statusCode());
        assertEquals(
                "invalid_parameter",
                json(response).get("error").getAsString()
        );
    }

    @Test
    void searchesWithAndAndCombinedFilters() throws Exception {
        createDatabase();

        var response = get(
                "/search?q=sea%20ship&author=JANE&language=en&year=2000"
        );
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals(1, body.get("total").getAsInt());

        JsonObject book = body.getAsJsonArray("results")
                .get(0).getAsJsonObject();

        assertEquals(1L, book.get("book_id").getAsLong());
        assertEquals("First book", book.get("title").getAsString());
        assertEquals(2000, book.get("year").getAsInt());
        assertEquals(
                "en",
                body.getAsJsonObject("filters").get("language").getAsString()
        );

        assertEquals(
                0,
                json(get("/search?q=sea%20missingterm"))
                        .get("total").getAsInt()
        );
    }

    @Test
    void returnsRequestedPageAndFullTotal() throws Exception {
        createDatabase();

        JsonObject body = json(get("/search?q=sea&offset=1&limit=1"));

        assertEquals(2, body.get("total").getAsInt());
        assertEquals(1, body.get("offset").getAsInt());
        assertEquals(1, body.get("limit").getAsInt());
        assertEquals(1, body.getAsJsonArray("results").size());
        assertEquals(
                2L,
                body.getAsJsonArray("results")
                        .get(0).getAsJsonObject()
                        .get("book_id").getAsLong()
        );
    }

    @Test
    void discardedAndEmptyQueriesReturnEmptyResults() throws Exception {
        createDatabase();

        for (String query : new String[]{"the%20and%20123", ""}) {
            var response = get("/search?q=" + query);

            assertEquals(200, response.statusCode());
            assertEquals(0, json(response).get("total").getAsInt());
            assertTrue(json(response).getAsJsonArray("results").isEmpty());
        }
    }

    @Test
    void returnsBookDetailsWithoutInternalPaths() throws Exception {
        createDatabase();

        var response = get("/books/1");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals(1L, body.get("book_id").getAsLong());
        assertEquals("First book", body.get("title").getAsString());
        assertEquals("Jane Austen", body.get("author").getAsString());
        assertEquals("en", body.get("language").getAsString());
        assertEquals(2000, body.get("year").getAsInt());
        assertFalse(body.has("header_path"));
        assertFalse(body.has("body_path"));
    }

    @Test
    void unknownBookReturnsNotFoundWithAndWithoutDatabase() throws Exception {
        assertEquals(404, get("/books/999").statusCode());

        createDatabase();

        var response = get("/books/999");

        assertEquals(404, response.statusCode());
        assertEquals("not_found", json(response).get("error").getAsString());
        assertTrue(json(response).has("message"));
    }

    @Test
    void malformedBookIdsReturnBadRequest() throws Exception {
        for (String id : new String[]{
                "abc", "0", "-1", "9223372036854775808"
        }) {
            var response = get("/books/" + id);

            assertEquals(400, response.statusCode(), id);
            assertEquals(
                    "invalid_book_id",
                    json(response).get("error").getAsString()
            );
        }
    }

    @Test
    void returnsAvailableLanguagesAndYearRange() throws Exception {
        createDatabase();

        var response = get("/search/filters");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());

        var languages = body.getAsJsonArray("languages");
        assertEquals(2, languages.size());
        assertEquals("en", languages.get(0).getAsString());
        assertEquals("es", languages.get(1).getAsString());

        var years = body.getAsJsonObject("year_range");
        assertEquals(2000, years.get("min").getAsInt());
        assertEquals(2001, years.get("max").getAsInt());
    }

    @Test
    void missingDatabaseReturnsEmptyFilterChoices() throws Exception {
        var response = get("/search/filters");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertTrue(body.getAsJsonArray("languages").isEmpty());
        assertTrue(
                body.getAsJsonObject("year_range").get("min").isJsonNull()
        );
        assertTrue(
                body.getAsJsonObject("year_range").get("max").isJsonNull()
        );
        assertFalse(Files.exists(dataDir.resolve("datamarts")));
    }

    @Test
    void statusReportsMissingDatamart() throws Exception {
        var response = get("/status");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("search", body.get("service").getAsString());
        assertEquals("running", body.get("status").getAsString());
        assertEquals("empty", body.get("datamart").getAsString());
        assertEquals(0, body.get("books_indexed").getAsLong());
        assertEquals(8, body.get("threads_min").getAsInt());
        assertEquals(250, body.get("threads_max").getAsInt());
        assertFalse(Files.exists(dataDir.resolve("datamarts")));
    }

    @Test
    void statusReportsPublishedBooks() throws Exception {
        createDatabase();

        var response = get("/status");
        JsonObject body = json(response);

        assertEquals(200, response.statusCode());
        assertEquals("ready", body.get("datamart").getAsString());
        assertEquals(2, body.get("books_indexed").getAsLong());
    }

    @Test
    void corsHeadersArePresentOnSuccessAndValidationErrors() throws Exception {
        for (String path : new String[]{
                "/search?q=sea",
                "/search"
        }) {
            var response = client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + app.port() + path)
                            )
                            .header("Origin", "http://localhost:3000")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );

            assertEquals(
                    path.equals("/search") ? 400 : 200,
                    response.statusCode()
            );
            assertEquals(
                    "*",
                    response.headers()
                            .firstValue("Access-Control-Allow-Origin")
                            .orElse("")
            );
        }
    }

    @Test
    void corsAcceptsPreflightRequests() throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + app.port() + "/search"
                                )
                        )
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        assertTrue(
                response.statusCode() == 200 || response.statusCode() == 204,
                "Unexpected preflight response: " + response.statusCode()
        );
        assertEquals(
                "*",
                response.headers()
                        .firstValue("Access-Control-Allow-Origin")
                        .orElse("")
        );
        assertTrue(
                response.headers()
                        .firstValue("Access-Control-Allow-Methods")
                        .orElse("")
                        .contains("GET")
        );
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + app.port() + path)
                ).GET().build(),
                HttpResponse.BodyHandlers.ofString()
        );
    }

    private static JsonObject json(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private void createDatabase() throws Exception {
        Path file = dataDir.resolve("datamarts").resolve("datamart.db");
        Files.createDirectories(file.getParent());

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + file.toUri().toASCIIString());
             var statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode = WAL");

            statement.execute("""
                    CREATE TABLE books (
                        book_id INTEGER PRIMARY KEY,
                        title TEXT NOT NULL,
                        author TEXT,
                        language TEXT,
                        release_year INTEGER
                    )
                    """);

            statement.execute("""
                    CREATE TABLE terms (
                        term TEXT PRIMARY KEY,
                        df INTEGER NOT NULL
                    )
                    """);

            statement.execute("""
                    CREATE TABLE postings (
                        term TEXT NOT NULL,
                        book_id INTEGER NOT NULL REFERENCES books(book_id),
                        tf INTEGER NOT NULL,
                        positions TEXT NOT NULL,
                        PRIMARY KEY (term, book_id)
                    )
                    """);

            statement.executeUpdate("""
                    INSERT INTO books VALUES
                    (1, 'First book', 'Jane Austen', 'en', 2000),
                    (2, 'Second book', 'Other Author', 'es', 2001)
                    """);

            statement.executeUpdate("""
                    INSERT INTO terms VALUES ('sea', 2), ('ship', 1)
                    """);

            statement.executeUpdate("""
                    INSERT INTO postings VALUES
                    ('sea', 1, 2, '0,1'),
                    ('ship', 1, 1, '2'),
                    ('sea', 2, 1, '0')
                    """);
        }
    }
}