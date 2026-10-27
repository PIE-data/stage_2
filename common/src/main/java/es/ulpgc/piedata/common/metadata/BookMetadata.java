package es.ulpgc.piedata.common.metadata;

/**
 * Descriptive metadata read from a Gutenberg header (Stage 1 SPEC §5.2).
 *
 * @param title       never null; {@code "Unknown"} when the header has no title
 * @param author      null when absent; life-span suffix removed; "Surname, Given" kept verbatim
 * @param language    ISO 639-1 code when known, otherwise the lowercased value; null when absent
 * @param releaseDate {@code YYYY-MM-DD} of the Gutenberg e-book release, null when absent or unparseable
 * @param releaseYear year of {@code releaseDate}, null when {@code releaseDate} is null
 *                    (the year filter of the search API; it is NOT the original publication year)
 */
public record BookMetadata(String title, String author, String language, String releaseDate, Integer releaseYear) {
}
