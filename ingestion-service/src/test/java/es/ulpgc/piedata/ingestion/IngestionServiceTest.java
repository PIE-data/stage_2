package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.ingestion.download.BookNotFoundException;
import es.ulpgc.piedata.ingestion.split.BookSplitter;
import es.ulpgc.piedata.ingestion.split.MarkersNotFoundException;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionServiceTest {

    private static final String VALID_BOOK =
            "Title: Test Book\nAuthor: Tester\n\n" +
                    BookSplitter.START_MARKER + " TEST ***\n" +
                    "Chapter 1\nHello World\n" +
                    BookSplitter.END_MARKER + " TEST ***\nLicence\n";

    @Test
    void successfullyIngestsBookAndPreventsDuplicates(@TempDir Path dataDir) throws Exception {
        Javalin mockServer = Javalin.create().start(0);
        try {
            mockServer.get("/cache/epub/123/pg123.txt", ctx -> ctx.result(VALID_BOOK));

            IngestionService service = new IngestionService(dataDir, "http://localhost:" + mockServer.port());
            IngestionService.IngestResult first = service.ingest(123L);

            assertTrue(first.newlyIngested());
            assertNotNull(first.receipt());
            assertEquals(123L, first.receipt().bookId());
            assertTrue(Files.exists(dataDir.resolve(first.receipt().headerPath())));
            assertTrue(Files.exists(dataDir.resolve(first.receipt().bodyPath())));

            IngestionService.IngestResult second = service.ingest(123L);
            assertFalse(second.newlyIngested());
            assertEquals(first.receipt().ingestedAt(), second.receipt().ingestedAt());

            assertEquals(1, service.listIngestedIds().size());
            assertEquals("downloaded", service.status(123L).status());
        } finally {
            mockServer.stop();
        }
    }

    @Test
    void recordsNotFoundInFailedBooks(@TempDir Path dataDir) {
        Javalin mockServer = Javalin.create().start(0);
        try {
            mockServer.get("/cache/epub/404/pg404.txt", ctx -> ctx.status(404));

            IngestionService service = new IngestionService(dataDir, "http://localhost:" + mockServer.port());
            assertThrows(BookNotFoundException.class, () -> service.ingest(404L));

            IngestionService.BookStatus status = service.status(404L);
            assertEquals("failed", status.status());
            assertEquals("NOT_FOUND", status.reason());
        } finally {
            mockServer.stop();
        }
    }

    @Test
    void recordsMissingMarkersInFailedBooks(@TempDir Path dataDir) {
        Javalin mockServer = Javalin.create().start(0);
        try {
            mockServer.get("/cache/epub/555/pg555.txt", ctx -> ctx.result("No markers in this text"));

            IngestionService service = new IngestionService(dataDir, "http://localhost:" + mockServer.port());
            assertThrows(MarkersNotFoundException.class, () -> service.ingest(555L));

            IngestionService.BookStatus status = service.status(555L);
            assertEquals("failed", status.status());
            assertEquals("NO_MARKERS", status.reason());
        } finally {
            mockServer.stop();
        }
    }
}