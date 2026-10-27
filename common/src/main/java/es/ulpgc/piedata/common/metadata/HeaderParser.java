package es.ulpgc.piedata.common.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of the Stage 1 metadata parser (Stage 1 SPEC §5.2 and {@code metadata.parse_header}
 * in the Python implementation). Must give exactly the same records: {@code GoldenMetadataTest}
 * checks it against {@code spec/golden/metadata_20.jsonl}.
 *
 * <p>Rules:
 * <ul>
 *   <li>A field starts only at column 0, with a known name followed by {@code :}, case-insensitive:
 *       Title, Author, Editor, Illustrator, Translator, Release date, Language, Credits.
 *       Only Title, Author, Language and Release date are kept.</li>
 *   <li>Blank lines do not end a field. An indented non-empty line continues the current field
 *       (joined with one space), except Release date, which is single-line.</li>
 *   <li>A non-empty line at column 0 that is not a known field ends the current field.</li>
 *   <li>A field that appears twice keeps its last value.</li>
 *   <li>Every value: whitespace runs collapsed to one space, then trimmed.</li>
 *   <li>Title missing or empty: {@code "Unknown"} (and a {@code MISSING_TITLE} warning).</li>
 *   <li>Author: a trailing {@code , YYYY-YYYY} life span is removed.</li>
 *   <li>Language: lowercased, then mapped to ISO 639-1 with {@code spec/language_map.txt};
 *       unknown names stay lowercased.</li>
 *   <li>Release date: {@code Month D, YYYY} optionally followed by {@code [eBook #id]}, English
 *       month names, converted to {@code YYYY-MM-DD}; anything else gives null.</li>
 * </ul>
 * Instances are immutable and thread-safe.
 */
public final class HeaderParser {

    private static final Logger LOG = LoggerFactory.getLogger(HeaderParser.class);

    /** Classpath location of the Stage 1 language map (copied from {@code spec/}). */
    public static final String LANGUAGE_MAP_RESOURCE = "spec/language_map.txt";

    /** Known field names, in the order the Stage 1 regular expression tries them. */
    private static final String[] KNOWN_FIELDS = {
            "Title", "Author", "Editor", "Illustrator", "Translator", "Release date", "Language", "Credits"};

    private static final String TITLE = "title";
    private static final String AUTHOR = "author";
    private static final String LANGUAGE = "language";
    private static final String RELEASE_DATE = "release date";

    /** {@code \d} and {@code \s} with Unicode meaning, as in Python 3 regular expressions. */
    private static final Pattern LIFE_SPAN =
            Pattern.compile(",\\s*\\d{4}\\s*[-–]\\s*\\d{4}\\s*$", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RELEASE =
            Pattern.compile("([A-Za-z]+) (\\d{1,2}), (\\d{4})(?: \\[.*\\])?", Pattern.UNICODE_CHARACTER_CLASS);

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12));

    private final Map<String, String> languageMap;

    public HeaderParser(Map<String, String> languageMap) {
        this.languageMap = Map.copyOf(languageMap);
    }

    /** Parser with the Stage 1 language map. */
    public static HeaderParser standard() {
        return StandardHolder.INSTANCE;
    }

    public BookMetadata parse(String header) {
        Map<String, String> fields = new HashMap<>();
        String current = null;

        for (String line : PyText.splitLines(header)) {
            String[] field = matchField(line);
            if (field != null) {
                // A known name at column 0 starts a new field (Editor, Credits... start one that is not kept).
                current = keptField(field[0]);
                if (current != null) {
                    fields.put(current, field[1]);
                }
                continue;
            }
            if (PyText.isBlank(line)) {
                continue;                                   // blank lines do not end a field
            }
            if (current != null && PyText.isSpace(line.codePointAt(0))) {
                if (!current.equals(RELEASE_DATE)) {        // nothing is ever appended to Release date
                    fields.put(current, fields.get(current) + " " + PyText.strip(line));
                }
                continue;
            }
            current = null;                                 // non-field line at column 0 ends the field
        }

        fields.replaceAll((key, value) -> PyText.collapseWhitespace(value));

        String title = fields.get(TITLE);
        if (title == null || title.isEmpty()) {
            title = "Unknown";
            LOG.warn("MISSING_TITLE");
        }

        String author = fields.get(AUTHOR);
        if (author != null) {
            author = PyText.strip(LIFE_SPAN.matcher(author).replaceAll(""));
        }

        String language = fields.get(LANGUAGE);
        if (language != null) {
            language = language.toLowerCase(Locale.ROOT);
            language = languageMap.getOrDefault(language, language);
        }

        LocalDate release = parseReleaseDate(fields.get(RELEASE_DATE));
        return new BookMetadata(title, author, language,
                release == null ? null : release.toString(),
                release == null ? null : release.getYear());
    }

    /** {@code {lowercase name, value}} when the line starts with a known field, else null. */
    private static String[] matchField(String line) {
        for (String name : KNOWN_FIELDS) {
            int n = name.length();
            if (line.length() > n && line.charAt(n) == ':' && line.regionMatches(true, 0, name, 0, n)) {
                return new String[] {name.toLowerCase(Locale.ROOT), PyText.lstrip(line.substring(n + 1))};
            }
        }
        return null;
    }

    private static String keptField(String lowercaseName) {
        switch (lowercaseName) {
            case TITLE: case AUTHOR: case LANGUAGE: case RELEASE_DATE:
                return lowercaseName;
            default:
                return null;
        }
    }

    /** {@code Month D, YYYY [eBook #id]} with English month names; null when not a valid date. */
    static LocalDate parseReleaseDate(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        Matcher m = RELEASE.matcher(value);
        if (!m.matches()) {
            return null;
        }
        Integer month = MONTHS.get(m.group(1).toLowerCase(Locale.ROOT));
        if (month == null) {
            return null;
        }
        int day = digits(m.group(2));
        int year = digits(m.group(3));
        if (year < 1) {
            return null;                                    // Python dates start at year 1
        }
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;                                    // e.g. February 30
        }
    }

    /** Value of a run of decimal digits of any script, as Python {@code int()} reads them. */
    private static int digits(String s) {
        int value = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            value = value * 10 + Character.digit(cp, 10);
            i += Character.charCount(cp);
        }
        return value;
    }

    /**
     * Reads a language map: {@code name=code} per line, UTF-8; blank lines and {@code #} comments
     * ignored; names are trimmed and lowercased.
     */
    public static Map<String, String> loadLanguageMap(InputStream in) {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                map.put(line.substring(0, eq).strip().toLowerCase(Locale.ROOT), line.substring(eq + 1).strip());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read language map", e);
        }
        return map;
    }

    private static final class StandardHolder {
        static final HeaderParser INSTANCE = load();

        private static HeaderParser load() {
            InputStream in = HeaderParser.class.getClassLoader().getResourceAsStream(LANGUAGE_MAP_RESOURCE);
            if (in == null) {
                throw new IllegalStateException(LANGUAGE_MAP_RESOURCE + " not found on the classpath");
            }
            return new HeaderParser(loadLanguageMap(in));
        }
    }
}
