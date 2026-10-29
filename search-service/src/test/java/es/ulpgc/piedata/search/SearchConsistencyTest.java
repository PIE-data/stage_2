package es.ulpgc.piedata.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class SearchConsistencyTest {

    @TempDir
    Path dataDir;

    private String writerUrl;

    @BeforeEach
    void prepareDatabase() throws Exception {
        Path file = dataDir.resolve("datamarts").resolve("datamart.db");
        Files.createDirectories(file.getParent());

        writerUrl = "jdbc:sqlite:" + file.toUri().toASCIIString();

        try (var connection = DriverManager.getConnection(writerUrl);
             var statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("PRAGMA busy_timeout = 30000");

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
                    (1, 'First book', 'Author A', 'en', 2000),
                    (2, 'Second book', 'Author B', 'en', 2001)
                    """);

            statement.executeUpdate("""
                    INSERT INTO terms VALUES ('sea', 1)
                    """);

            statement.executeUpdate("""
                    INSERT INTO postings VALUES ('sea', 1, 1, '0')
                    """);
        }
    }

    @Test
    void searchSeesNewBookOnlyAfterWriterCommits() throws Exception {
        SearchService service = new SearchService(dataDir);
        SearchRequest request = SearchRequest.parse(
                "sea", null, null, null, null, null
        );

        try (var writer = DriverManager.getConnection(writerUrl)) {
            writer.setAutoCommit(false);
            addThirdBook(writer);

            // The writer has changed the data but has not committed.
            var beforeCommit = service.search(request);

            assertEquals(1, beforeCommit.total());
            assertEquals(1, beforeCommit.results().size());
            assertEquals(
                    Math.log(2.0),
                    beforeCommit.results().get(0).score(),
                    1e-12
            );

            writer.commit();

            // A new Search request sees the complete committed update.
            var afterCommit = service.search(request);

            assertEquals(2, afterCommit.total());
            assertEquals(2, afterCommit.results().size());
            assertEquals(
                    Math.log(3.0 / 2),
                    afterCommit.results().get(0).score(),
                    1e-12
            );
        }
    }

    @Test
    void readTransactionKeepsSnapshotAcrossWriterCommit() throws Exception {
        SearchDatabase database = new SearchDatabase(dataDir);

        try (var writer = DriverManager.getConnection(writerUrl)) {
            writer.setAutoCommit(false);

            var snapshot = database.read(reader -> {
                // The first SELECT establishes the reader's snapshot.
                int booksBefore = scalar(
                        reader, "SELECT COUNT(*) FROM books"
                );

                addThirdBook(writer);
                writer.commit();

                // These reads must still see the earlier snapshot.
                int booksAfter = scalar(
                        reader, "SELECT COUNT(*) FROM books"
                );
                int postingsAfter = scalar(
                        reader, "SELECT COUNT(*) FROM postings"
                );

                return new int[]{
                        booksBefore, booksAfter, postingsAfter
                };
            }).orElseThrow();

            assertArrayEquals(new int[]{2, 2, 1}, snapshot);
        }

        // After the old read transaction ends, a new one sees the update.
        var latest = database.read(reader -> new int[]{
                scalar(reader, "SELECT COUNT(*) FROM books"),
                scalar(reader, "SELECT COUNT(*) FROM postings")
        }).orElseThrow();

        assertArrayEquals(new int[]{3, 2}, latest);
    }

    private static void addThirdBook(Connection writer) throws SQLException {
        try (var statement = writer.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO books VALUES
                    (3, 'Third book', 'Author C', 'en', 2002)
                    """);

            statement.executeUpdate("""
                    INSERT INTO postings VALUES ('sea', 3, 1, '0')
                    """);

            statement.executeUpdate("""
                    UPDATE terms SET df = 2 WHERE term = 'sea'
                    """);
        }
    }

    private static int scalar(Connection connection, String sql)
            throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}