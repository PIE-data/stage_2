package es.ulpgc.piedata.common.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.text.SpecFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rules of Stage 1 SPEC §5.2. The parameterized cases are the Stage 1 unit fixture
 * ({@code spec/golden/metadata_headers.json}, copied from the Python tests).
 */
class HeaderParserTest {

    private final HeaderParser parser = HeaderParser.standard();

    static List<JsonObject> stage1Cases() throws IOException {
        String json = Files.readString(SpecFiles.golden().resolve("metadata_headers.json"), StandardCharsets.UTF_8);
        return StreamSupport.stream(JsonParser.parseString(json).getAsJsonArray().spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .collect(Collectors.toList());
    }

    @ParameterizedTest(name = "{index}")
    @MethodSource("stage1Cases")
    void stage1UnitCases(JsonObject c) {
        JsonObject expected = c.getAsJsonObject("expected");
        BookMetadata m = parser.parse(c.get("header").getAsString());
        String name = c.get("name").getAsString();
        assertEquals(text(expected, "title"), m.title(), name);
        assertEquals(text(expected, "author"), m.author(), name);
        assertEquals(text(expected, "language"), m.language(), name);
        assertEquals(text(expected, "release_date"), m.releaseDate(), name);
    }

    static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @Test
    void standardHeader() {
        BookMetadata m = parser.parse("Title: Pride and Prejudice\nAuthor: Austen, Jane, 1775-1817\n"
                + "Language: English\nRelease Date: June 1, 1998 [eBook #1342]\n");
        assertEquals(new BookMetadata("Pride and Prejudice", "Austen, Jane", "en", "1998-06-01", 1998), m);
    }

    @Test
    void blankLinesDoNotEndAFieldButColumnZeroTextDoes() {
        BookMetadata m = parser.parse("Title: A Journey\n\n    Around the World\nNote: x\n    not appended\n");
        assertEquals("A Journey Around the World", m.title());
    }

    @Test
    void releaseDateIsSingleLine() {
        BookMetadata m = parser.parse("Title: T\nRelease date: May 3, 2005 [eBook #8035]\n"
                + "                Most recently updated: January 1, 2020\n");
        assertEquals("2005-05-03", m.releaseDate());
        assertEquals(2005, m.releaseYear());
    }

    @Test
    void unknownOrInvalidValues() {
        BookMetadata m = parser.parse("Author:\nLanguage: Klingon\nRelease Date: February 30, 2004\n");
        assertEquals("Unknown", m.title(), "missing title");
        assertEquals("", m.author(), "present but empty author stays empty, as in Stage 1");
        assertEquals("klingon", m.language(), "unmapped language kept lowercased");
        assertNull(m.releaseDate(), "impossible date");
        assertNull(m.releaseYear());
    }

    @Test
    void lastValueWinsAndCaseOfFieldNamesIsIgnored() {
        BookMetadata m = parser.parse("TITLE: first\ntitle: second\nLANGUAGE: FRENCH\n");
        assertEquals("second", m.title());
        assertEquals("fr", m.language());
    }

    @Test
    void notKeptFieldsStillEndTheCurrentField() {
        BookMetadata m = parser.parse("Title: Main\nEditor: Someone\n    continuation of editor\n");
        assertEquals("Main", m.title());
    }

    @Test
    void whitespaceIsCollapsedLikePython() {
        // U+00A0 (no-break space) and U+3000 are whitespace for Python, not for Java's strip().
        BookMetadata m = parser.parse("Title:  Two  Words 　\n");
        assertEquals("Two Words", m.title());
    }

    @Test
    void windowsAndOldMacLineEndings() {
        assertEquals("A B", parser.parse("Title: A\r\n  B\r\n").title());
        assertEquals("A B", parser.parse("Title: A\r  B\r").title());
    }

    @Test
    void releaseDateFormats() {
        assertEquals("2017-12-31", HeaderParser.parseReleaseDate("december 31, 2017 [EBook #56271]").toString());
        assertEquals("0999-03-05", HeaderParser.parseReleaseDate("March 5, 0999").toString());
        assertNull(HeaderParser.parseReleaseDate("March 5, 0000"), "year 0 does not exist");
        assertNull(HeaderParser.parseReleaseDate("Jan 1, 2000"), "abbreviated month");
        assertNull(HeaderParser.parseReleaseDate("May 1, 2010 extra"));
        assertNull(HeaderParser.parseReleaseDate(""));
        assertNull(HeaderParser.parseReleaseDate(null));
    }
}
