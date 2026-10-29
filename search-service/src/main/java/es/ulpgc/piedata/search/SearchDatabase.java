package es.ulpgc.piedata.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only access to the published datamart.
 * Each operation has its own connection and read transaction.
 */
public final class SearchDatabase {

    private final Path file;

    public SearchDatabase(Path dataDir) {
        this.file = dataDir.resolve("datamarts")
                .resolve("datamart.db")
                .toAbsolutePath()
                .normalize();
    }

    @FunctionalInterface
    public interface ReadOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * Returns empty only when the database file does not exist.
     * Other access and SQL errors are propagated.
     * Operations must return a non-null value.
     */
    public <T> Optional<T> read(ReadOperation<T> operation)
            throws SQLException {

        if (Files.notExists(file)) {
            return Optional.empty();
        }

        String url = "jdbc:sqlite:" + file.toUri().toASCIIString()
                + "?mode=ro";

        try (Connection connection = DriverManager.getConnection(url)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                statement.execute("PRAGMA busy_timeout = 30000");
            }

            connection.setAutoCommit(false);

            try {
                T result = Objects.requireNonNull(
                        operation.execute(connection),
                        "Read operations must return a non-null value"
                );
                connection.commit();
                return Optional.of(result);
            } catch (SQLException | RuntimeException e) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    e.addSuppressed(rollbackError);
                }
                throw e;
            }
        }
    }
}