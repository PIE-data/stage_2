package es.ulpgc.piedata.common.datamart;

import es.ulpgc.piedata.common.metadata.BookMetadata;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writer side of the datamart ({@code docs/DATAMART.md}): one SQLite file with books, terms and
 * postings. Only the Indexing service writes in production; tests of other services may use it to
 * build a small datamart.
 *
 * <p>Guarantees:
 * <ul>
 *   <li><b>Atomic publication</b>: a book is written in one transaction (metadata + postings + df),
 *       so a reader sees all of it or nothing.</li>
 *   <li><b>Idempotent</b>: writing a book again replaces its previous version; the result is the
 *       same as writing it once.</li>
 *   <li><b>Several writers</b> (threads with their own {@code Datamart}, or other processes):
 *       {@code BEGIN IMMEDIATE} takes SQLite's single write lock up front, others wait up to
 *       {@code busy_timeout} (30 s) and then fail with {@link SQLException}; the caller retries
 *       (message redelivery, EVENTS.md §5).</li>
 * </ul>
 * One instance = one JDBC connection: not shareable between threads (methods are synchronized
 * only as a safety net). Give each thread its own instance.
 */
public final class Datamart implements AutoCloseable {

    /** {@code <data.dir>/datamarts/datamart.db}. */
    public static Path defaultFile(Path dataDir) {
        return dataDir.resolve("datamarts").resolve("datamart.db");
    }

    static final int BUSY_TIMEOUT_MS = 30_000;

    /**
     * Default SQLite page cache per connection, in MiB. SQLite's own default is only ~2 MiB, which
     * makes every B-tree insert go back to disk once the index is larger than that (Stage 1 SPEC
     * §5.3 used 256 MiB for the same reason). Configurable: {@code datamart.cache-mb}.
     */
    public static final int DEFAULT_CACHE_MB = 256;

    private static final String INSERT_BOOK =
            "INSERT OR REPLACE INTO books (book_id, title, author, language, release_date, release_year,"
                    + " header_path, body_path, body_bytes, sha256, n_tokens, ingested_at, indexed_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_POSTING =
            "INSERT INTO postings (term, book_id, tf, positions) VALUES (?, ?, ?, ?)";
    // Removing a previous version: only the terms of that book are touched (never a full scan).
    private static final String DECREMENT_DF =
            "UPDATE terms SET df = df - 1 WHERE term IN (SELECT term FROM postings WHERE book_id = ?)";
    private static final String DELETE_UNUSED_TERMS =
            "DELETE FROM terms WHERE df <= 0 AND term IN (SELECT term FROM postings WHERE book_id = ?)";
    private static final String DELETE_POSTINGS = "DELETE FROM postings WHERE book_id = ?";

    private final Connection connection;
    private final Clock clock;

    private Datamart(Connection connection, Clock clock) {
        this.connection = connection;
        this.clock = clock;
    }

    /** Opens (creating it if needed) the datamart file for writing, with the default cache. */
    public static Datamart open(Path file) throws SQLException {
        return open(file, Clock.systemUTC(), DEFAULT_CACHE_MB);
    }

    /** As {@link #open(Path)}, with the clock used for {@code indexed_at} (tests). */
    public static Datamart open(Path file, Clock clock) throws SQLException {
        return open(file, clock, DEFAULT_CACHE_MB);
    }

    /**
     * Opens the datamart with an explicit page-cache size.
     *
     * @param cacheMb SQLite page cache of this connection in MiB (memory is used only as pages are read)
     */
    public static Datamart open(Path file, Clock clock, int cacheMb) throws SQLException {
        if (cacheMb < 1) {
            throw new IllegalArgumentException("cacheMb must be at least 1, got " + cacheMb);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + file.getParent(), e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MS);   // first: the next ones may wait
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = NORMAL");
            s.execute("PRAGMA foreign_keys = ON");
            s.execute("PRAGMA cache_size = " + (-1024L * cacheMb));   // negative = KiB, not pages
        }
        Datamart d = new Datamart(c, clock);
        d.createSchema();
        return d;
    }

    private void createSchema() throws SQLException {
        inTransaction(() -> {
            try (Statement s = connection.createStatement()) {
                for (String sql : schemaStatements()) {
                    s.execute(sql);
                }
            }
        });
    }

    /** Statements of {@code datamart/schema.sql}, comments removed. */
    static List<String> schemaStatements() {
        try (InputStream in = Datamart.class.getClassLoader().getResourceAsStream("datamart/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("datamart/schema.sql not found on the classpath");
            }
            StringBuilder sql = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                int comment = line.indexOf("--");
                sql.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
            }
            return java.util.Arrays.stream(sql.toString().split(";"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Books written per chunk during a rebuild (postings of a chunk are sorted before insertion). */
    static final int REBUILD_CHUNK = 32;

    private static final String INSERT_TERM = "INSERT INTO terms (term, df) VALUES (?, ?)";
    private static final String ADD_DF =
            "INSERT INTO terms (term, df) VALUES (?, ?) ON CONFLICT(term) DO UPDATE SET df = df + excluded.df";
    private static final String DELETE_BOOK = "DELETE FROM books WHERE book_id = ?";

    /** Writes (or replaces) one book in its own transaction. */
    public void write(IndexedBook book) throws SQLException {
        writeAll(List.of(book));
    }

    /**
     * Writes (or replaces) several books in ONE transaction: all of them become visible together,
     * or none does. Much cheaper per book than separate transactions: the postings of the whole
     * batch are inserted in key order and each term's df is updated once. If the same book appears
     * twice, the last version wins.
     */
    public synchronized void writeAll(Collection<IndexedBook> books) throws SQLException {
        String indexedAt = now();
        Map<Long, IndexedBook> unique = new LinkedHashMap<>();
        for (IndexedBook b : books) {
            unique.remove(b.bookId());
            unique.put(b.bookId(), b);
        }
        inTransaction(() -> {
            removePrevious(unique.keySet());
            Map<String, Integer> df = insertBooksAndPostings(unique.values(), indexedAt);
            try (PreparedStatement add = connection.prepareStatement(ADD_DF)) {
                addTerms(add, df);
            }
        });
    }

    /** Removes a book (metadata and postings) in one transaction; nothing happens if it is absent. */
    public synchronized void remove(long bookId) throws SQLException {
        inTransaction(() -> {
            removePrevious(List.of(bookId));
            try (PreparedStatement p = connection.prepareStatement(DELETE_BOOK)) {
                p.setLong(1, bookId);
                p.executeUpdate();
            }
        });
    }

    /**
     * Replaces the whole content with {@code books} in ONE transaction: readers keep seeing the old
     * index until the commit, then the new one; on any error nothing changes. Other writers wait
     * until the rebuild commits (or give up after {@code busy_timeout}).
     *
     * <p>Fast path for a full load: the tables are dropped and recreated (no row-by-row deletes),
     * secondary indexes are built once at the end, postings are inserted in sorted chunks and df is
     * counted in memory and written once per term. The iterable is consumed lazily, chunk by chunk,
     * so memory stays bounded by one chunk plus the term counts.
     *
     * @return the number of books written
     * @throws IllegalArgumentException if a book id appears twice
     */
    public synchronized int rebuild(Iterable<IndexedBook> books) throws SQLException {
        String indexedAt = now();
        int[] count = {0};
        List<String> schema = schemaStatements();
        inTransaction(() -> {
            try (Statement s = connection.createStatement()) {
                // children first: dropping a parent table with foreign keys on checks its children
                s.execute("DROP TABLE IF EXISTS postings");
                s.execute("DROP TABLE IF EXISTS terms");
                s.execute("DROP TABLE IF EXISTS books");
                for (String sql : schema) {
                    if (!sql.startsWith("CREATE INDEX")) {
                        s.execute(sql);
                    }
                }
            }
            Map<String, Integer> df = new HashMap<>();
            Set<Long> seen = new HashSet<>();
            List<IndexedBook> chunk = new ArrayList<>(REBUILD_CHUNK);
            for (IndexedBook b : books) {
                if (!seen.add(b.bookId())) {
                    throw new IllegalArgumentException("book " + b.bookId() + " appears twice in the rebuild");
                }
                chunk.add(b);
                if (chunk.size() == REBUILD_CHUNK) {
                    insertBooksAndPostings(chunk, indexedAt).forEach((t, n) -> df.merge(t, n, Integer::sum));
                    count[0] += chunk.size();
                    chunk.clear();
                }
            }
            insertBooksAndPostings(chunk, indexedAt).forEach((t, n) -> df.merge(t, n, Integer::sum));
            count[0] += chunk.size();
            try (PreparedStatement insert = connection.prepareStatement(INSERT_TERM)) {
                addTerms(insert, df);
            }
            try (Statement s = connection.createStatement()) {
                for (String sql : schema) {
                    if (sql.startsWith("CREATE INDEX")) {
                        s.execute(sql);
                    }
                }
            }
        });
        return count[0];
    }

    private String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** Takes the previous version of these books out of postings and df (inside a transaction). */
    private void removePrevious(Collection<Long> bookIds) throws SQLException {
        try (PreparedStatement decrement = connection.prepareStatement(DECREMENT_DF);
             PreparedStatement deleteTerms = connection.prepareStatement(DELETE_UNUSED_TERMS);
             PreparedStatement deletePostings = connection.prepareStatement(DELETE_POSTINGS)) {
            for (long id : bookIds) {
                for (PreparedStatement p : List.of(decrement, deleteTerms, deletePostings)) {
                    p.setLong(1, id);
                    p.executeUpdate();
                }
            }
        }
    }

    /**
     * Inserts the metadata rows and the postings of {@code books} (inside a transaction; their
     * previous versions must already be gone). Postings of all the books are inserted in
     * (term, book_id) order, the order of the table's B-tree.
     *
     * @return for each term, how many of these books contain it (the df increment)
     */
    private Map<String, Integer> insertBooksAndPostings(Collection<IndexedBook> books, String indexedAt)
            throws SQLException {
        Map<String, Integer> df = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement insertBook = connection.prepareStatement(INSERT_BOOK)) {
            for (IndexedBook b : books) {
                bindBook(insertBook, b, indexedAt);
                insertBook.addBatch();
                for (Map.Entry<String, int[]> e : b.postings().entrySet()) {
                    rows.add(new Object[] {e.getKey(), b.bookId(), e.getValue()});
                    df.merge(e.getKey(), 1, Integer::sum);
                }
            }
            insertBook.executeBatch();
        }
        rows.sort((x, y) -> {
            int c = CanonicalExport.UTF8_ORDER.compare((String) x[0], (String) y[0]);
            return c != 0 ? c : Long.compare((Long) x[1], (Long) y[1]);
        });
        try (PreparedStatement insertPosting = connection.prepareStatement(INSERT_POSTING)) {
            for (Object[] r : rows) {
                int[] positions = (int[]) r[2];
                insertPosting.setString(1, (String) r[0]);
                insertPosting.setLong(2, (Long) r[1]);
                insertPosting.setInt(3, positions.length);
                insertPosting.setString(4, CanonicalExport.csv(positions));
                insertPosting.addBatch();
            }
            insertPosting.executeBatch();
        }
        return df;
    }

    /** Writes df values in term order with {@code statement} (INSERT_TERM or ADD_DF). */
    private static void addTerms(PreparedStatement statement, Map<String, Integer> df) throws SQLException {
        List<String> terms = new ArrayList<>(df.keySet());
        terms.sort(CanonicalExport.UTF8_ORDER);
        for (String t : terms) {
            statement.setString(1, t);
            statement.setInt(2, df.get(t));
            statement.addBatch();
        }
        statement.executeBatch();
    }

    private static void bindBook(PreparedStatement p, IndexedBook b, String indexedAt) throws SQLException {
        BookMetadata m = b.metadata();
        p.setLong(1, b.bookId());
        p.setString(2, m.title());
        setNullableString(p, 3, m.author());
        setNullableString(p, 4, m.language());
        setNullableString(p, 5, m.releaseDate());
        if (m.releaseYear() == null) {
            p.setNull(6, Types.INTEGER);
        } else {
            p.setInt(6, m.releaseYear());
        }
        p.setString(7, b.headerPath());
        p.setString(8, b.bodyPath());
        p.setLong(9, b.bodyBytes());
        p.setString(10, b.sha256());
        p.setInt(11, b.nTokens());
        p.setString(12, b.ingestedAt());
        p.setString(13, indexedAt);
    }

    private static void setNullableString(PreparedStatement p, int index, String value) throws SQLException {
        if (value == null) {
            p.setNull(index, Types.VARCHAR);
        } else {
            p.setString(index, value);
        }
    }

    /** Counts for {@code GET /index/status}. */
    public record Stats(long books, long terms, long postings, String lastIndexedAt) {
    }

    public synchronized Stats stats() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet r = s.executeQuery("SELECT (SELECT COUNT(*) FROM books), (SELECT COUNT(*) FROM terms),"
                     + " (SELECT COUNT(*) FROM postings), (SELECT MAX(indexed_at) FROM books)")) {
            r.next();
            return new Stats(r.getLong(1), r.getLong(2), r.getLong(3), r.getString(4));
        }
    }

    /** True when the book is in the datamart. */
    public synchronized boolean contains(long bookId) throws SQLException {
        try (PreparedStatement p = connection.prepareStatement("SELECT 1 FROM books WHERE book_id = ?")) {
            p.setLong(1, bookId);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    /**
     * Terms whose stored {@code df} differs from the real number of postings (must always be empty;
     * used by tests and as a health check).
     */
    public synchronized long inconsistentTerms() throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet r = s.executeQuery("SELECT COUNT(*) FROM ("
                     + " SELECT t.term FROM terms t LEFT JOIN postings p ON p.term = t.term"
                     + " GROUP BY t.term HAVING COUNT(p.book_id) <> MAX(t.df)"
                     + " UNION ALL"
                     + " SELECT p.term FROM postings p LEFT JOIN terms t ON t.term = p.term WHERE t.term IS NULL)")) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Streams the canonical export (Stage 1 SPEC §7) of the whole index to {@code out}. */
    public synchronized void exportCanonical(OutputStream out) throws SQLException {
        // SQLite's default BINARY collation compares UTF-8 bytes: exactly the order SPEC §7 requires.
        try (Statement s = connection.createStatement();
             ResultSet r = s.executeQuery("SELECT term, book_id, tf, positions FROM postings ORDER BY term, book_id")) {
            CanonicalExport export = new CanonicalExport(out);
            while (r.next()) {
                export.add(r.getString(1), r.getLong(2), r.getInt(3), r.getString(4));
            }
            export.finish();
        }
    }

    public byte[] exportCanonical() throws SQLException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        exportCanonical(bytes);
        return bytes.toByteArray();
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws SQLException;
    }

    /** BEGIN IMMEDIATE ... COMMIT, or ROLLBACK on any error. */
    private void inTransaction(SqlWork work) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("BEGIN IMMEDIATE");
            try {
                work.run();
                s.execute("COMMIT");
            } catch (SQLException | RuntimeException e) {
                try {
                    s.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }
}
