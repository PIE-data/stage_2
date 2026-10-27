package es.ulpgc.piedata.common.metadata;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.text.SpecFiles;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;

import static es.ulpgc.piedata.common.metadata.HeaderParserTest.text;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Java parser must give the same records as the Stage 1 Python parser on the 20 golden
 * books ({@code spec/golden/metadata_20.jsonl}, generated with {@code metadata.parse_header}
 * from {@code spec/golden/headers/}).
 */
class GoldenMetadataTest {

    static List<JsonObject> fixture() throws IOException {
        return Files.readAllLines(SpecFiles.golden().resolve("metadata_20.jsonl"), StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .map(line -> JsonParser.parseString(line).getAsJsonObject())
                .collect(Collectors.toList());
    }

    @ParameterizedTest(name = "book {0}")
    @MethodSource("fixture")
    void matchesStage1(JsonObject expected) {
        long bookId = expected.get("book_id").getAsLong();
        BookMetadata m = HeaderParser.standard().parse(SpecFiles.goldenHeader(bookId));

        assertEquals(text(expected, "title"), m.title(), "title of " + bookId);
        assertEquals(text(expected, "author"), m.author(), "author of " + bookId);
        assertEquals(text(expected, "language"), m.language(), "language of " + bookId);
        assertEquals(text(expected, "release_date"), m.releaseDate(), "release date of " + bookId);
    }
}
