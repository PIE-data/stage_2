package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.datalake.HashLayout;
import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.datalake.ReceiptStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * A data folder with the 20 golden books already "ingested": header and body in the hash layout
 * plus a receipt each, exactly what the Ingestion service (#3) will produce.
 */
final class TestWorkspace {

    static final Path GOLDEN = Path.of("..", "spec", "golden");

    private TestWorkspace() {
    }

    static List<Long> goldenIds() throws IOException {
        return Files.readAllLines(GOLDEN.resolve("manifest_20.txt"), StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).map(Long::parseLong).sorted().toList();
    }

    static String expectedCanonicalSha256() throws IOException {
        return Files.readString(GOLDEN.resolve("expected.sha256")).strip().split("\\s+")[0];
    }

    /** Puts every golden book into {@code dataDir}; returns their ids. */
    static List<Long> ingestGolden(Path dataDir) throws IOException {
        List<Long> ids = goldenIds();
        for (long id : ids) {
            ingest(dataDir, id);
        }
        return ids;
    }

    static void ingest(Path dataDir, long id) throws IOException {
        Path header = dataDir.resolve(HashLayout.headerPath(id));
        Path body = dataDir.resolve(HashLayout.bodyPath(id));
        Files.createDirectories(header.getParent());
        Files.copy(GOLDEN.resolve("headers").resolve(id + ".header.txt"), header);
        try (InputStream in = new GZIPInputStream(Files.newInputStream(
                GOLDEN.resolve("bodies").resolve(id + ".body.txt.gz")))) {
            Files.write(body, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        new ReceiptStore(dataDir).write(new Receipt(id, HashLayout.headerPath(id), HashLayout.bodyPath(id),
                "2026-10-01T00:00:00Z"));
    }
}
