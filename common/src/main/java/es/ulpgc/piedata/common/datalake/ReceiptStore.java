package es.ulpgc.piedata.common.datalake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Ingestion receipts of the {@code hash} layout: {@code <data.dir>/control/ingestion/hash/<id>.json}.
 * A receipt exists only once both files of the book are complete, so "has a receipt" means
 * "ready to be indexed" (docs/ARCHITECTURE.md §4).
 */
public final class ReceiptStore {

    private final Path dataDir;
    private final Path folder;

    public ReceiptStore(Path dataDir) {
        this.dataDir = dataDir;
        this.folder = dataDir.resolve("control").resolve("ingestion").resolve("hash");
    }

    public Path dataDir() {
        return dataDir;
    }

    public Path file(long bookId) {
        return folder.resolve(bookId + ".json");
    }

    /** The receipt of a book, or empty if the book is not (completely) ingested. */
    public Optional<Receipt> read(long bookId) {
        Path f = file(bookId);
        try {
            String json = Files.readString(f, StandardCharsets.UTF_8);
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            return Optional.of(new Receipt(
                    o.get("book_id").getAsLong(),
                    o.get("header_path").getAsString(),
                    o.get("body_path").getAsString(),
                    o.get("ingested_at").getAsString()));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        } catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
            throw new IllegalStateException("malformed receipt " + f, e);
        }
    }

    /** Ids of every ingested book, ascending. */
    public List<Long> ids() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.matches("\\d+\\.json"))
                    .forEach(name -> ids.add(Long.parseLong(name.substring(0, name.length() - 5))));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list " + folder, e);
        }
        ids.sort(null);
        return ids;
    }

    /**
     * Writes a receipt atomically: temporary file, flushed to disk, then renamed (Stage 1 SPEC §2.4).
     * Readers see either no receipt or the complete one.
     */
    public void write(Receipt receipt) {
        Path target = file(receipt.bookId());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(folder);
            try (FileChannel ch = FileChannel.open(part, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ch.write(StandardCharsets.UTF_8.encode(receipt.toJsonLine()));
                ch.force(true);
            }
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }
}
