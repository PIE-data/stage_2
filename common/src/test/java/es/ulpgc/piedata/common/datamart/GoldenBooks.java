package es.ulpgc.piedata.common.datamart;

import es.ulpgc.piedata.common.text.SpecFiles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** The 20 golden books, indexed in memory (test helper). */
public final class GoldenBooks {

    private static List<IndexedBook> cache;

    private GoldenBooks() {
    }

    public static synchronized List<IndexedBook> all() {
        if (cache == null) {
            List<IndexedBook> books = new ArrayList<>();
            BookIndexer indexer = BookIndexer.standard();
            for (long id : ids()) {
                books.add(indexer.index(id, SpecFiles.goldenHeader(id), body(id),
                        "datalake/" + id + ".header.txt", "datalake/" + id + ".body.txt", "2026-10-01T00:00:00Z"));
            }
            cache = List.copyOf(books);
        }
        return cache;
    }

    public static List<Long> ids() {
        try {
            return Files.readAllLines(SpecFiles.golden().resolve("manifest_20.txt"), StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(s -> !s.isEmpty()).map(Long::parseLong).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] body(long id) {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(
                SpecFiles.golden().resolve("bodies").resolve(id + ".body.txt.gz")))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String expectedCanonicalSha256() {
        try {
            return Files.readString(SpecFiles.golden().resolve("expected.sha256")).strip().split("\\s+")[0];
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
