package es.ulpgc.piedata.common.datalake;

/**
 * Ingestion receipt (Stage 1 SPEC §1.2): proof that a book's header and body are completely in the
 * datalake. Written by Ingestion after both files, read by Indexing.
 *
 * @param bookId     Gutenberg id
 * @param headerPath relative to {@code data.dir}, {@code /} separators
 * @param bodyPath   relative to {@code data.dir}, {@code /} separators
 * @param ingestedAt UTC ISO 8601, whole seconds, e.g. {@code 2026-10-22T10:00:03Z}
 */
public record Receipt(long bookId, String headerPath, String bodyPath, String ingestedAt) {

    /**
     * The exact Stage 1 file content: one line, keys in this order, no spaces, LF at the end.
     * Paths never contain characters that need JSON escaping (digits, letters, '/', '.').
     */
    public String toJsonLine() {
        return "{\"book_id\":" + bookId + ",\"header_path\":\"" + headerPath + "\",\"body_path\":\"" + bodyPath
                + "\",\"ingested_at\":\"" + ingestedAt + "\"}\n";
    }
}
