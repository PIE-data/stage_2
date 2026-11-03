package es.ulpgc.piedata.ingestion.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

public final class FailedBooksLog {

    private static final Logger LOG = LoggerFactory.getLogger(FailedBooksLog.class);

    private final Path failedFile;

    public FailedBooksLog(Path dataDir) {
        this.failedFile = dataDir.resolve("control").resolve("failed_books.txt");
    }

    public synchronized void record(long bookId, String reason) {
        try {
            Files.createDirectories(failedFile.getParent());
            String timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            String line = bookId + "\t" + reason + "\t" + timestamp + "\n";
            Files.writeString(failedFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            LOG.warn("Recorded failure for book_id={} reason={}", bookId, reason);
        } catch (IOException e) {
            LOG.error("Failed to append to failed_books.txt for book_id={}", bookId, e);
        }
    }

    public Optional<String> readReason(long bookId) {
        if (!Files.exists(failedFile)) {
            return Optional.empty();
        }
        try (BufferedReader reader = Files.newBufferedReader(failedFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t");
                if (parts.length >= 2 && Long.parseLong(parts[0]) == bookId) {
                    return Optional.of(parts[1]);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return Optional.empty();
    }
}