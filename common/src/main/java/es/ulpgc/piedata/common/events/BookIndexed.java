package es.ulpgc.piedata.common.events;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public record BookIndexed(String event, long book_id, String indexed_at) {

    public static final String EVENT_NAME = "BookIndexed";

    public static BookIndexed of(long bookId) {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be greater than 0: " + bookId);
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return new BookIndexed(EVENT_NAME, bookId, now);
    }
}