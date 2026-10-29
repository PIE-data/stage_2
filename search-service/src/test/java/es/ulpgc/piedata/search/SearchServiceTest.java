package es.ulpgc.piedata.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {

    @TempDir
    Path dataDir;

    private SearchService service;

    @BeforeEach
    void prepareDatabase() throws Exception {
        Path file = dataDir.resolve("datamarts").resolve("datamart.db");
        Files.createDirectories(file.getParent());

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + file.toUri().toASCIIString());
             var statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode = WAL");

            // Only the columns read by Search are needed in this fixture.
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
                    (1, 'First book', 'José Silva', 'en', 2000),
                    (2, 'Second book', 'Other Author', 'es', 2001),
                    (3, 'Third book', 'José Silva', 'en', 2000),
                    (4, 'Fourth book', NULL, 'fr', NULL)
                    """);

            statement.executeUpdate("""
                    INSERT INTO terms VALUES ('sea', 3), ('ship', 2)
                    """);

            statement.executeUpdate("""
                    INSERT INTO postings VALUES
                    ('sea', 1, 2, '0,1'),
                    ('ship', 1, 1, '2'),
                    ('sea', 2, 1, '0'),
                    ('sea', 3, 2, '0,1'),
                    ('ship', 3, 1, '2')
                    """);
        }

        service = new SearchService(dataDir);
    }

    @Test
    void ranksByScoreThenBookId() throws Exception {
        var result = service.search(request("sea", null, null, null, null, null));

        assertEquals(3, result.total());
        assertEquals(
                java.util.List.of(1L, 3L, 2L),
                result.results().stream()
                        .map(SearchService.BookHit::bookId)
                        .toList()
        );
        assertEquals(
                2 * Math.log(4.0 / 3),
                result.results().get(0).score(),
                1e-12
        );
    }

    @Test
    void requiresAllDistinctQueryTerms() throws Exception {
        var result = service.search(
                request("SEA sea ship", null, null, null, null, null)
        );

        assertEquals(2, result.total());
        assertEquals(
                2 * Math.log(4.0 / 3) + Math.log(4.0 / 2),
                result.results().get(0).score(),
                1e-12
        );

        assertEquals(
                0,
                service.search(
                        request("sea missingterm", null, null, null, null, null)
                ).total()
        );
    }

    @Test
    void appliesEachFilterAndTheirCombination() throws Exception {
        assertEquals(2, service.search(
                request("sea", "JOSÉ", null, null, null, null)
        ).total());

        assertEquals(1, service.search(
                request("sea", null, "es", null, null, null)
        ).total());

        assertEquals(2, service.search(
                request("sea", null, null, "2000", null, null)
        ).total());

        var combined = service.search(
                request("sea", "JOSÉ", "en", "2000", null, null)
        );

        assertEquals(2, combined.total());

        // Filtering must not change corpus-wide N or df.
        assertEquals(
                2 * Math.log(4.0 / 3),
                combined.results().get(0).score(),
                1e-12
        );

        assertEquals(0, service.search(
                request("sea", "JOSÉ", "es", "2000", null, null)
        ).total());
    }

    @Test
    void treatsAuthorWildcardsAsLiteralCharacters() throws Exception {
        assertEquals(0, service.search(
                request("sea", "%", null, null, null, null)
        ).total());
    }

    @Test
    void paginatesWithoutChangingTotal() throws Exception {
        var page = service.search(
                request("sea", null, null, null, "1", "1")
        );

        assertEquals(3, page.total());
        assertEquals(1, page.results().size());
        assertEquals(3L, page.results().get(0).bookId());

        var beyond = service.search(
                request("sea", null, null, null, "100", "20")
        );

        assertEquals(3, beyond.total());
        assertTrue(beyond.results().isEmpty());
    }

    @Test
    void discardedTermsMatchNothing() throws Exception {
        var result = service.search(
                request("the and 123", null, null, null, null, null)
        );

        assertEquals(0, result.total());
        assertTrue(result.results().isEmpty());
    }

    @Test
    void missingDatamartProducesEmptyResults() throws Exception {
        SearchService emptyService =
                new SearchService(dataDir.resolve("not-created"));

        var result = emptyService.search(
                request("sea", null, null, null, null, null)
        );

        assertEquals(0, result.total());
        assertTrue(result.results().isEmpty());
        assertFalse(Files.exists(dataDir.resolve("not-created")));
    }

    private static SearchRequest request(
            String query,
            String author,
            String language,
            String year,
            String offset,
            String limit
    ) {
        return SearchRequest.parse(
                query, author, language, year, offset, limit
        );
    }
}