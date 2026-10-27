package es.ulpgc.piedata.common.datamart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical export of the inverted index (Stage 1 SPEC §7): the same bytes for the same corpus in
 * every language and every backend. Its SHA-256 on the 20 golden books must equal
 * {@code spec/golden/expected.sha256}: that proves the Java indexer builds exactly the Stage 1 index.
 *
 * <p>Format: one JSON object, UTF-8, no whitespace, no trailing newline; keys = terms in UTF-8 byte
 * order; value {@code {"df":n,"postings":[[id,tf,[pos,...]],...]}}, postings by ascending id.
 * Terms only contain letters, digits and apostrophes (tokenizer rules), so no JSON escaping is
 * ever needed; the writer still refuses a term that would need it rather than emit wrong bytes.
 */
public final class CanonicalExport {

    /**
     * UTF-8 byte order. Java's {@code String.compareTo} uses UTF-16 order, which differs for
     * characters outside the Basic Multilingual Plane; code-point order equals UTF-8 byte order.
     */
    public static final Comparator<String> UTF8_ORDER = (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    };

    private final OutputStream out;
    private String currentTerm;
    private final StringBuilder postings = new StringBuilder();
    private int df;
    private boolean first = true;

    /** Streams the export to {@code out}; call {@link #add} in term order, then {@link #finish}. */
    public CanonicalExport(OutputStream out) {
        this.out = out;
    }

    /**
     * Adds one posting. Calls must come ordered by term (UTF-8 byte order) and then by book id.
     *
     * @param positionsCsv comma-separated ascending positions, as stored in the datamart
     */
    public void add(String term, long bookId, int tf, String positionsCsv) {
        if (!term.equals(currentTerm)) {
            flushTerm();
            currentTerm = term;
        }
        if (df > 0) {
            postings.append(',');
        }
        postings.append('[').append(bookId).append(',').append(tf).append(",[").append(positionsCsv).append("]]");
        df++;
    }

    /** Writes the last term and the closing brace. */
    public void finish() {
        flushTerm();
        write(first ? "{}" : "}");
    }

    private void flushTerm() {
        if (currentTerm == null) {
            return;
        }
        write((first ? "{" : ",") + jsonString(currentTerm) + ":{\"df\":" + df + ",\"postings\":[" + postings + "]}");
        first = false;
        postings.setLength(0);
        df = 0;
        currentTerm = null;
    }

    private void write(String s) {
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String jsonString(String term) {
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (c == '"' || c == '\\' || c < 0x20) {
                throw new IllegalArgumentException("term needs JSON escaping, not allowed by the tokenizer: " + term);
            }
        }
        return '"' + term + '"';
    }

    /** Canonical export of books held in memory (tests and tools; the service exports from SQLite). */
    public static byte[] of(Collection<IndexedBook> books) {
        TreeMap<String, List<long[]>> byTerm = new TreeMap<>(UTF8_ORDER);
        Map<Long, IndexedBook> byId = new TreeMap<>();
        for (IndexedBook b : books) {
            byId.put(b.bookId(), b);                         // last version of a book wins
        }
        for (IndexedBook b : byId.values()) {
            for (String term : b.postings().keySet()) {
                byTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(new long[] {b.bookId()});
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CanonicalExport export = new CanonicalExport(bytes);
        byTerm.forEach((term, ids) -> {
            for (long[] id : ids) {                          // ids ascending: byId is a TreeMap
                int[] positions = byId.get(id[0]).postings().get(term);
                export.add(term, id[0], positions.length, csv(positions));
            }
        });
        export.finish();
        return bytes.toByteArray();
    }

    static String csv(int[] positions) {
        StringBuilder sb = new StringBuilder(positions.length * 6);
        for (int i = 0; i < positions.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(positions[i]);
        }
        return sb.toString();
    }
}
