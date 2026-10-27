package es.ulpgc.piedata.common.datamart;

import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.metadata.HeaderParser;
import es.ulpgc.piedata.common.text.Tokenizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns the header and body of one book into an {@link IndexedBook}: metadata from the header
 * parser, postings from the tokenizer (positions counted before filtering, Stage 1 SPEC §3.3).
 * Pure computation, no I/O: thread-safe.
 */
public final class BookIndexer {

    private final HeaderParser headerParser;
    private final Tokenizer tokenizer;

    public BookIndexer(HeaderParser headerParser, Tokenizer tokenizer) {
        this.headerParser = headerParser;
        this.tokenizer = tokenizer;
    }

    /** Indexer with the Stage 1 language map and stop words. */
    public static BookIndexer standard() {
        return new BookIndexer(HeaderParser.standard(), Tokenizer.english());
    }

    /**
     * Reads an ingested book from the datalake (paths of its receipt, relative to {@code dataDir})
     * and indexes it. Invalid UTF-8 is replaced, never fatal (as in Stage 1).
     *
     * @throws java.nio.file.NoSuchFileException if the receipt points to a missing file
     */
    public IndexedBook index(Path dataDir, Receipt r) throws IOException {
        byte[] header = Files.readAllBytes(dataDir.resolve(r.headerPath()));
        byte[] body = Files.readAllBytes(dataDir.resolve(r.bodyPath()));
        return index(r.bookId(), new String(header, StandardCharsets.UTF_8), body,
                r.headerPath(), r.bodyPath(), r.ingestedAt());
    }

    public IndexedBook index(long bookId, String header, byte[] body,
                             String headerPath, String bodyPath, String ingestedAt) {
        List<Tokenizer.Token> tokens = tokenizer.tokenize(new String(body, StandardCharsets.UTF_8));

        // Group positions by term. Positions arrive in ascending order, so each list is sorted.
        Map<String, List<Integer>> grouped = new HashMap<>();
        for (Tokenizer.Token t : tokens) {
            grouped.computeIfAbsent(t.term(), k -> new ArrayList<>()).add(t.position());
        }
        // Terms in UTF-8 byte order: rows are then inserted in primary-key order (faster B-tree inserts).
        Map<String, int[]> postings = new LinkedHashMap<>();
        TreeMap<String, List<Integer>> sorted = new TreeMap<>(CanonicalExport.UTF8_ORDER);
        sorted.putAll(grouped);
        sorted.forEach((term, positions) ->
                postings.put(term, positions.stream().mapToInt(Integer::intValue).toArray()));

        return new IndexedBook(bookId, headerParser.parse(header), headerPath, bodyPath,
                body.length, sha256(body), tokens.size(), ingestedAt, postings);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
