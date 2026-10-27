package es.ulpgc.piedata.common.datamart;

import es.ulpgc.piedata.common.metadata.BookMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static es.ulpgc.piedata.common.datamart.GoldenDatamartTest.sha256;
import static org.junit.jupiter.api.Assertions.*;

/** Behaviour required by docs/DATAMART.md: metadata row, replacement, atomicity, concurrent writers. */
class DatamartTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-27T12:34:56.789Z"), ZoneOffset.UTC);

    private static IndexedBook book(long id, String title, Map<String, int[]> postings) {
        return new IndexedBook(id, new BookMetadata(title, "Austen, Jane", "en", "1998-06-01", 1998),
                "datalake/h", "datalake/b", 123, "abc", 7, "2026-10-01T00:00:00Z", postings);
    }

    private static Map<String, int[]> postings(Object... termAndPositions) {
        Map<String, int[]> m = new LinkedHashMap<>();
        for (int i = 0; i < termAndPositions.length; i += 2) {
            m.put((String) termAndPositions[i], (int[]) termAndPositions[i + 1]);
        }
        return m;
    }

    @Test
    void storesTheMetadataRow(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("datamarts/datamart.db");
        try (Datamart d = Datamart.open(file, FIXED)) {
            d.write(book(1342, "Pride and Prejudice", postings("pride", new int[] {0, 9})));
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
             Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT title, author, language, release_date, release_year,"
                     + " body_bytes, sha256, n_tokens, ingested_at, indexed_at FROM books WHERE book_id = 1342")) {
            assertTrue(r.next());
            assertEquals("Pride and Prejudice", r.getString(1));
            assertEquals("Austen, Jane", r.getString(2));
            assertEquals("en", r.getString(3));
            assertEquals("1998-06-01", r.getString(4));
            assertEquals(1998, r.getInt(5));
            assertEquals(123, r.getLong(6));
            assertEquals("abc", r.getString(7));
            assertEquals(7, r.getInt(8));
            assertEquals("2026-10-01T00:00:00Z", r.getString(9));
            assertEquals("2026-10-27T12:34:56Z", r.getString(10), "whole seconds, UTC");
        }
    }

    @Test
    void reindexingReplacesTheOldVersion(@TempDir Path dir) throws Exception {
        try (Datamart d = Datamart.open(dir.resolve("datamart.db"))) {
            d.write(book(1, "A", postings("whale", new int[] {1}, "sea", new int[] {2})));
            d.write(book(2, "B", postings("whale", new int[] {5})));
            d.write(book(1, "A", postings("ship", new int[] {3})));   // new version: other terms

            assertEquals("{\"ship\":{\"df\":1,\"postings\":[[1,1,[3]]]},"
                            + "\"whale\":{\"df\":1,\"postings\":[[2,1,[5]]]}}",
                    new String(d.exportCanonical(), java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(2, d.stats().books());
            assertEquals(2, d.stats().terms(), "'sea' is gone, 'whale' kept for book 2");
            assertEquals(0, d.inconsistentTerms());
        }
    }

    @Test
    void aFailedBatchLeavesNothingBehind(@TempDir Path dir) throws Exception {
        try (Datamart d = Datamart.open(dir.resolve("datamart.db"))) {
            IndexedBook good = book(1, "Good", postings("whale", new int[] {1}));
            IndexedBook bad = book(2, null, postings("sea", new int[] {1}));   // title NOT NULL -> fails
            assertThrows(SQLException.class, () -> d.writeAll(List.of(good, bad)));
            assertFalse(d.contains(1), "the good book of the failed batch must not be visible");
            assertEquals(0, d.stats().terms());
            d.write(good);                                   // the connection is still usable
            assertTrue(d.contains(1));
        }
    }

    @Test
    void openingTwiceKeepsTheData(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("datamart.db");
        try (Datamart d = Datamart.open(file)) {
            d.write(book(1, "A", postings("whale", new int[] {1})));
        }
        try (Datamart d = Datamart.open(file)) {             // schema creation is idempotent
            assertTrue(d.contains(1));
        }
    }

    @Test
    void concurrentWritersProduceTheStage1Index(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("datamart.db");
        Datamart.open(file).close();                         // create the schema once
        List<IndexedBook> books = GoldenBooks.all();
        int writers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                int me = w;
                done.add(pool.submit(() -> {
                    // each writer has its own connection, like separate replicas; each book written twice
                    try (Datamart d = Datamart.open(file)) {
                        for (int i = me; i < books.size(); i += writers) {
                            d.write(books.get(i));
                        }
                        for (int i = me; i < books.size(); i += writers) {
                            d.write(books.get((i + 1) % books.size()));
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
        try (Datamart d = Datamart.open(file)) {
            assertEquals(GoldenBooks.expectedCanonicalSha256(), sha256(d.exportCanonical()));
            assertEquals(0, d.inconsistentTerms());
        }
    }
}
