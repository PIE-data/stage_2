package es.ulpgc.piedata.common.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/** Locates the repository's {@code spec/} folder from a module's test run. */
public final class SpecFiles {

    private SpecFiles() {
    }

    /** Maven runs tests with the module folder as working directory, so spec/ is one level up. */
    public static Path specDir() {
        for (Path candidate : new Path[] {Path.of("..", "spec"), Path.of("spec")}) {
            if (Files.isDirectory(candidate.resolve("golden"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("spec/ folder not found from " + Path.of("").toAbsolutePath());
    }

    public static Path golden() {
        return specDir().resolve("golden");
    }

    /** Header of a golden book as produced by the Stage 1 splitter. */
    public static String goldenHeader(long bookId) {
        Path file = golden().resolve("headers").resolve(bookId + ".header.txt");
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    /** Body of a golden book as produced by the Stage 1 splitter. */
    public static String goldenBody(long bookId) {
        Path file = golden().resolve("bodies").resolve(bookId + ".body.txt.gz");
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
