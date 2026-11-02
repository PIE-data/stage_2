package es.ulpgc.piedata.ingestion;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

class BookSplitterTest {

    private static final Path GOLDEN = Path.of("..", "spec", "golden");

    @Test
    void matchesStage1GoldenBooksByteIdentical() throws Exception {
        BookSplitter splitter = new BookSplitter();
        List<Long> ids = Files.readAllLines(GOLDEN.resolve("manifest_20.txt"), StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(Long::parseLong)
                .toList();

        for (long id : ids) {
            String raw = Files.readString(GOLDEN.resolve(id + ".txt"), StandardCharsets.UTF_8);
            SplitResult result = splitter.split(raw);

            String expectedHeader = Files.readString(GOLDEN.resolve("headers").resolve(id + ".header.txt"), StandardCharsets.UTF_8);
            assertEquals(expectedHeader, result.header(), "Header mismatch on book " + id);

            String expectedBody;
            try (InputStream in = new GZIPInputStream(Files.newInputStream(GOLDEN.resolve("bodies").resolve(id + ".body.txt.gz")))) {
                expectedBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(expectedBody, result.body(), "Body mismatch on book " + id);
        }
    }

    @Test
    void handlesSyntheticQuotedEndMarker() throws Exception {
        BookSplitter splitter = new BookSplitter();
        String raw = Files.readString(GOLDEN.resolve("synthetic_quoted_end.txt"), StandardCharsets.UTF_8);
        SplitResult result = splitter.split(raw);

        assertTrue(result.body().contains(BookSplitter.END_MARKER));
        assertTrue(result.body().contains("FEAR.  And it is horrible!"));
    }

    @Test
    void throwsWhenMarkersMissing() {
        BookSplitter splitter = new BookSplitter();
        assertThrows(MarkersNotFoundException.class, () -> splitter.split("No markers here"));
        assertThrows(MarkersNotFoundException.class, () -> splitter.split(BookSplitter.START_MARKER + "\nSome text without end"));
        assertThrows(MarkersNotFoundException.class, () -> splitter.split("Text before\n" + BookSplitter.END_MARKER));
    }
}