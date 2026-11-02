package es.ulpgc.piedata.common.events;

public record BookDownloaded(
        String event,
        long book_id,
        String header_path,
        String body_path,
        String ingested_at
) {
    public static final String EVENT_NAME = "BookDownloaded";

    public static BookDownloaded of(long bookId, String headerPath, String bodyPath, String ingestedAt) {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be greater than 0: " + bookId);
        }
        return new BookDownloaded(EVENT_NAME, bookId, headerPath, bodyPath, ingestedAt);
    }
}