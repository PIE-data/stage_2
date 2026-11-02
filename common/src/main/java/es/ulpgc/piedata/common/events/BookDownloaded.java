package es.ulpgc.piedata.common.events;

public record BookDownloaded(
        String event,
        long bookId,
        String headerPath,
        String bodyPath,
        String ingestedAt
) {
    public static final String EVENT_NAME = "BookDownloaded";

    public static BookDownloaded of(long bookId, String headerPath, String bodyPath, String ingestedAt) {
        if (bookId <= 0) {
            throw new IllegalArgumentException("bookId must be greater than 0: " + bookId);
        }
        return new BookDownloaded(EVENT_NAME, bookId, headerPath, bodyPath, ingestedAt);
    }
}
