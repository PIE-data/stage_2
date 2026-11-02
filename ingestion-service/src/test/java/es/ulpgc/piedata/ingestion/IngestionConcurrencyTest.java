package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.datalake.ReceiptStore;
import es.ulpgc.piedata.ingestion.split.BookSplitter;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestionConcurrencyTest {

    @Test
    void twoReplicasCompeteOnSame100BooksWithoutDuplicatesOrPartFiles(@TempDir Path dataDir) throws Exception {
        int bookCount = 100;
        Javalin mockHttp = Javalin.create().start(0);

        String sampleText = "Title: Concurrency Book\nAuthor: Tester\n\n" +
                BookSplitter.START_MARKER + " BOOK ***\n" +
                "Sample content for concurrent testing\n" +
                BookSplitter.END_MARKER + " BOOK ***\n";

        for (int i = 1; i <= bookCount; i++) {
            int id = i;
            mockHttp.get("/cache/epub/" + id + "/pg" + id + ".txt", ctx -> ctx.result(sampleText));
        }

        try {
            String sourceUrl = "http://localhost:" + mockHttp.port();
            IngestionService replica1 = new IngestionService(dataDir, sourceUrl);
            IngestionService replica2 = new IngestionService(dataDir, sourceUrl);

            List<Long> targetIds = new ArrayList<>();
            for (long i = 1; i <= bookCount; i++) {
                targetIds.add(i);
            }

            List<Callable<Void>> tasks = new ArrayList<>();
            for (long id : targetIds) {
                tasks.add(() -> {
                    replica1.ingest(id);
                    return null;
                });
                tasks.add(() -> {
                    replica2.ingest(id);
                    return null;
                });
            }
            Collections.shuffle(tasks);

            ExecutorService executor = Executors.newFixedThreadPool(8);
            List<Future<Void>> futures = executor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                f.get();
            }
            executor.shutdown();

            ReceiptStore receiptStore = new ReceiptStore(dataDir);
            assertEquals(bookCount, receiptStore.ids().size());

            try (Stream<Path> stream = Files.walk(dataDir)) {
                List<Path> partFiles = stream.filter(p -> p.toString().endsWith(".part")).toList();
                assertTrue(partFiles.isEmpty(), "Found orphaned .part files: " + partFiles);
            }
        } finally {
            mockHttp.stop();
        }
    }
}