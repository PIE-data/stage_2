package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.datalake.HashLayout;
import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.ingestion.download.BookNotFoundException;
import es.ulpgc.piedata.ingestion.download.GutenbergDownloader;
import es.ulpgc.piedata.ingestion.split.BookSplitter;
import es.ulpgc.piedata.ingestion.split.MarkersNotFoundException;
import es.ulpgc.piedata.ingestion.split.SplitResult;
import es.ulpgc.piedata.ingestion.storage.AtomicWriter;
import es.ulpgc.piedata.ingestion.storage.BookClaim;
import es.ulpgc.piedata.ingestion.storage.FailedBooksLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

public final class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    private final Path dataDir;
    private final GutenbergDownloader downloader;
    private final BookSplitter splitter;
    private final ReceiptStore receiptStore;
    private final FailedBooksLog failedBooksLog;

    public IngestionService(Path dataDir, String sourceBaseUrl) {
        this.dataDir = dataDir;
        this.downloader = new GutenbergDownloader(sourceBaseUrl);
        this.splitter = new BookSplitter();
        this.receiptStore = new ReceiptStore(dataDir);
        this.failedBooksLog = new FailedBooksLog(dataDir);
    }

    public record IngestResult(Receipt receipt, boolean newlyIngested) {
    }

    public record BookStatus(long bookId, String status, String ingestedAt, String headerPath, String bodyPath, String reason) {
    }

    public IngestResult ingest(long bookId) throws Exception {
        Optional<Receipt> existing = receiptStore.read(bookId);
        if (existing.isPresent()) {
            return new IngestResult(existing.get(), false);
        }

        try (BookClaim claim = BookClaim.acquire(dataDir, bookId)) {
            existing = receiptStore.read(bookId);
            if (existing.isPresent()) {
                return new IngestResult(existing.get(), false);
            }

            try {
                String rawText = downloader.download(bookId);
                SplitResult split = splitter.split(rawText);

                Path headerTarget = dataDir.resolve(HashLayout.headerPath(bookId));
                Path bodyTarget = dataDir.resolve(HashLayout.bodyPath(bookId));

                AtomicWriter.write(headerTarget, split.header());
                AtomicWriter.write(bodyTarget, split.body());

                String ingestedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
                Receipt receipt = new Receipt(bookId, HashLayout.headerPath(bookId), HashLayout.bodyPath(bookId), ingestedAt);
                receiptStore.write(receipt);

                LOG.info("Successfully ingested book_id={} into datalake", bookId);
                return new IngestResult(receipt, true);
            } catch (BookNotFoundException e) {
                failedBooksLog.record(bookId, "NOT_FOUND");
                throw e;
            } catch (MarkersNotFoundException e) {
                failedBooksLog.record(bookId, "NO_MARKERS");
                throw e;
            }
        }
    }

    public BookStatus status(long bookId) {
        Optional<Receipt> receipt = receiptStore.read(bookId);
        if (receipt.isPresent()) {
            Receipt r = receipt.get();
            return new BookStatus(bookId, "downloaded", r.ingestedAt(), r.headerPath(), r.bodyPath(), null);
        }

        Optional<String> failure = failedBooksLog.readReason(bookId);
        if (failure.isPresent()) {
            return new BookStatus(bookId, "failed", null, null, null, failure.get());
        }

        return new BookStatus(bookId, "not_found", null, null, null, null);
    }

    public List<Long> listIngestedIds() {
        return receiptStore.ids();
    }

    public Optional<Receipt> getReceipt(long bookId) {
        return receiptStore.read(bookId);
    }
}