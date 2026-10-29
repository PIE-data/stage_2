package es.ulpgc.piedata.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

class SearchDatabaseTest {

    @TempDir
    Path dataDir;

    @Test
    void missingDatabaseReturnsEmptyWithoutCreatingFiles() throws Exception {
        SearchDatabase database = new SearchDatabase(dataDir);

        var result = database.read(connection -> {
            fail("A missing database must not execute the operation");
            return 0;
        });

        assertTrue(result.isEmpty());
        assertFalse(Files.exists(dataDir.resolve("datamarts")));
    }

    @Test
    void readsAnExistingDatabase() throws Exception {
        createDatabase();

        var result = new SearchDatabase(dataDir)
                .read(SearchDatabaseTest::countBooks);

        assertEquals(1, result.orElseThrow().intValue());
    }

    @Test
    void rejectsWritesAndLeavesDataUnchanged() throws Exception {
        createDatabase();
        SearchDatabase database = new SearchDatabase(dataDir);

        assertThrows(SQLException.class, () -> database.read(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO books(book_id) VALUES (2)"
                );
            }
            return 0;
        }));

        assertEquals(
                1,
                database.read(SearchDatabaseTest::countBooks)
                        .orElseThrow()
                        .intValue()
        );
    }

    @Test
    void detectsDatabaseCreatedAfterAnEarlierRequest() throws Exception {
        SearchDatabase database = new SearchDatabase(dataDir);

        assertTrue(database.read(SearchDatabaseTest::countBooks).isEmpty());

        createDatabase();

        assertEquals(
                1,
                database.read(SearchDatabaseTest::countBooks)
                        .orElseThrow()
                        .intValue()
        );
    }

    private void createDatabase() throws Exception {
        Path file = dataDir.resolve("datamarts").resolve("datamart.db");
        Files.createDirectories(file.getParent());

        String url = "jdbc:sqlite:" + file.toUri().toASCIIString();

        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute(
                    "CREATE TABLE books (book_id INTEGER PRIMARY KEY)"
            );
            statement.executeUpdate(
                    "INSERT INTO books(book_id) VALUES (1)"
            );
        }
    }

    private static int countBooks(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM books")) {
            rows.next();
            return rows.getInt(1);
        }
    }
}