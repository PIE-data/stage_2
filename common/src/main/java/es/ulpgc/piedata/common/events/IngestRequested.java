package es.ulpgc.piedata.common.events;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

public record IngestRequested(String event, long book_id, String requested_at) {
    public static final String EVENT_NAME = "IngestRequested";

    public static IngestRequested of(long bookId) {
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be greater than 0: " + bookId);
        }
        String now = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(ChronoUnit.SECONDS));
        return new IngestRequested(EVENT_NAME, bookId, now);
    }
}
