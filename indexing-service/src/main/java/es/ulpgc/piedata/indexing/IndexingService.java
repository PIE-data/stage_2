package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.common.datamart.BookIndexer;
import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.datamart.IndexedBook;
import es.ulpgc.piedata.common.http.ApiException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What the Indexing service does, independent of how it is called (HTTP now, broker messages in
 * piece D): index one book, rebuild everything, report the state.
 *
 * <p>Concurrency: reading and tokenizing a book (CPU work) runs in the caller's thread, so several
 * requests are processed in parallel; only the final write goes through the single
 * {@link Datamart} connection, one writer at a time, as SQLite requires.
 */
public final class IndexingService implements AutoCloseable {

    private final Path dataDir;
    private final ReceiptStore receipts;
    private final BookIndexer indexer;
    private final Path datamartFile;
    private final Datamart datamart;
    private final AtomicBoolean rebuilding = new AtomicBoolean(false);

    public IndexingService(Path dataDir) throws SQLException {
        this.dataDir = dataDir;
        this.receipts = new ReceiptStore(dataDir);
        this.indexer = BookIndexer.standard();
        this.datamartFile = Datamart.defaultFile(dataDir);
        this.datamart = Datamart.open(datamartFile);
    }

    /** Result of indexing one book. */
    public record UpdateResult(long bookId, int distinctTerms, int tokens, long elapsedMs) {
    }

    /** Result of a rebuild. */
    public record RebuildResult(int booksProcessed, long elapsedMs) {
    }

    /** Snapshot for {@code GET /index/status}. */
    public record Status(long booksIndexed, long terms, long postings, String lastUpdate,
                         double indexSizeMb, boolean rebuildInProgress) {
    }

    /**
     * Indexes (or re-indexes) one ingested book. Idempotent: calling it twice gives the same datamart.
     *
     * @throws ApiException 404 {@code not_ingested} if the book has no receipt,
     *                      409 {@code datalake_inconsistent} if the receipt points to missing files,
     *                      503 {@code datamart_busy} if the datamart stays locked (retry later)
     */
    public UpdateResult update(long bookId) {
        long start = System.nanoTime();
        Receipt receipt = receipts.read(bookId).orElseThrow(() ->
                new ApiException(404, "not_ingested", "book " + bookId + " has no ingestion receipt"));
        IndexedBook book = load(receipt);
        try {
            datamart.write(book);
        } catch (SQLException e) {
            throw busy(e);
        }
        int tokens = book.nTokens();
        return new UpdateResult(bookId, book.postings().size(), tokens, (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * Rebuilds the whole datamart from every receipt, in one transaction: searches keep seeing the
     * previous index until the new one is complete. Only one rebuild at a time.
     *
     * @throws ApiException 409 {@code rebuild_in_progress}, 409 {@code datalake_inconsistent},
     *                      503 {@code datamart_busy}
     */
    public RebuildResult rebuild() {
        if (!rebuilding.compareAndSet(false, true)) {
            throw new ApiException(409, "rebuild_in_progress", "a rebuild is already running");
        }
        long start = System.nanoTime();
        try {
            List<Long> ids = receipts.ids();
            Iterable<IndexedBook> books = () -> new Iterator<>() {
                private int next = 0;

                @Override
                public boolean hasNext() {
                    return next < ids.size();
                }

                @Override
                public IndexedBook next() {
                    long id = ids.get(next++);
                    return load(receipts.read(id).orElseThrow(() -> new ApiException(409, "datalake_inconsistent",
                            "receipt of book " + id + " disappeared during the rebuild")));
                }
            };
            int processed = datamart.rebuild(books);
            return new RebuildResult(processed, (System.nanoTime() - start) / 1_000_000);
        } catch (SQLException e) {
            throw busy(e);
        } finally {
            rebuilding.set(false);
        }
    }

    public Status status() {
        try {
            Datamart.Stats s = datamart.stats();
            long bytes = size(datamartFile) + size(datamartFile.resolveSibling(datamartFile.getFileName() + "-wal"));
            double mb = Math.round(bytes / 1024.0 / 1024.0 * 10) / 10.0;
            return new Status(s.books(), s.terms(), s.postings(), s.lastIndexedAt(), mb, rebuilding.get());
        } catch (SQLException e) {
            throw busy(e);
        }
    }

    /** Reads header and body of an ingested book and indexes it (no datamart access). */
    private IndexedBook load(Receipt r) {
        try {
            byte[] header = Files.readAllBytes(dataDir.resolve(r.headerPath()));
            byte[] body = Files.readAllBytes(dataDir.resolve(r.bodyPath()));
            // Invalid UTF-8 is replaced, never fatal (as in Stage 1).
            return indexer.index(r.bookId(), new String(header, StandardCharsets.UTF_8), body,
                    r.headerPath(), r.bodyPath(), r.ingestedAt());
        } catch (NoSuchFileException e) {
            throw new ApiException(409, "datalake_inconsistent",
                    "book " + r.bookId() + " has a receipt but its file is missing: " + e.getFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read book " + r.bookId(), e);
        }
    }

    private static ApiException busy(SQLException e) {
        return new ApiException(503, "datamart_busy", "datamart unavailable, retry later: " + e.getMessage());
    }

    private static long size(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public void close() throws SQLException {
        datamart.close();
    }
}
