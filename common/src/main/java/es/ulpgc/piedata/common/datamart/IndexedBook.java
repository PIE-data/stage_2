package es.ulpgc.piedata.common.datamart;

import es.ulpgc.piedata.common.metadata.BookMetadata;

import java.util.Map;

/**
 * Everything the datamart stores about one book: its metadata row and its postings.
 * Built by {@link BookIndexer}, written by {@link Datamart#write}.
 *
 * @param bookId     Gutenberg id
 * @param metadata   parsed header
 * @param headerPath header file, relative to {@code data.dir}, {@code /} separators
 * @param bodyPath   body file, relative to {@code data.dir}, {@code /} separators
 * @param bodyBytes  size of the body file in bytes
 * @param sha256     SHA-256 of the body file bytes, lowercase hex
 * @param nTokens    number of kept tokens (document length)
 * @param ingestedAt copied from the ingestion receipt (UTC ISO 8601, whole seconds)
 * @param postings   term → ascending token positions; {@code tf} is the array length
 */
public record IndexedBook(
        long bookId,
        BookMetadata metadata,
        String headerPath,
        String bodyPath,
        long bodyBytes,
        String sha256,
        int nTokens,
        String ingestedAt,
        Map<String, int[]> postings) {
}
