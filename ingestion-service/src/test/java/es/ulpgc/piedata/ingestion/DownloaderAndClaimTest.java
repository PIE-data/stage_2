package es.ulpgc.piedata.ingestion;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloaderAndClaimTest {

    @Test
    void downloaderThrowsNotFoundOn404WithoutRetry() {
        Javalin mockServer = Javalin.create().start(0);
        try {
            mockServer.get("/cache/epub/999999/pg999999.txt", ctx -> ctx.status(404));
            GutenbergDownloader downloader = new GutenbergDownloader("http://localhost:" + mockServer.port());
            assertThrows(BookNotFoundException.class, () -> downloader.download(999999));
        } finally {
            mockServer.stop();
        }
    }

    @Test
    void bookClaimEnforcesMutualExclusion(@TempDir Path tempDir) throws Exception {
        long bookId = 42L;
        AtomicInteger concurrentExecutions = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        Runnable task = () -> {
            try (BookClaim claim = BookClaim.acquire(tempDir, bookId)) {
                int current = concurrentExecutions.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                Thread.sleep(100);
                concurrentExecutions.decrementAndGet();
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                latch.countDown();
            }
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        latch.await();

        assertEquals(1, maxConcurrent.get());
    }
}