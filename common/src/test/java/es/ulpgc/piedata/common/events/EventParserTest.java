package es.ulpgc.piedata.common.events;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EventParserTest {
    @Test
    void parsesValidIngestRequestedEvent() {
        String json = "{\"event\":\"IngestRequested\",\"book_id\":1342,\"requested_at\":\"2026-10-22T10:00:00Z\"}";
        IngestRequested event = EventParser.parse(json, IngestRequested.class, IngestRequested.EVENT_NAME);
        assertNotNull(event);
        assertEquals(1342L, event.book_id());
        assertEquals("IngestRequested", event.event());
        assertEquals("2026-10-22T10:00:00Z", event.requested_at());
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        String json = "{\"event\":\"IngestRequested\",\"book_id\":1342,\"requested_at\":\"2026-10-22T10:00:00Z\",\"extra_field\":\"ignored\"}";
        IngestRequested event = EventParser.parse(json, IngestRequested.class, IngestRequested.EVENT_NAME);
        assertNotNull(event);
        assertEquals(1342L, event.book_id());
    }
    @Test
    void rejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () ->
                EventParser.parse("not a json string", IngestRequested.class, IngestRequested.EVENT_NAME));
    }
    @Test
    void rejectsMismatchedEventName() {
        String json = "{\"event\":\"WrongName\",\"book_id\":1342}";
        assertThrows(IllegalArgumentException.class, () ->
                EventParser.parse(json, IngestRequested.class, IngestRequested.EVENT_NAME));
    }
    @Test
    void rejectsNonPositiveBookId() {
        String zeroId = "{\"event\":\"IngestRequested\",\"book_id\":0}";
        assertThrows(IllegalArgumentException.class, () ->
                EventParser.parse(zeroId, IngestRequested.class, IngestRequested.EVENT_NAME));
        String negativeId = "{\"event\":\"IngestRequested\",\"book_id\":-5}";
        assertThrows(IllegalArgumentException.class, () ->
                EventParser.parse(negativeId, IngestRequested.class, IngestRequested.EVENT_NAME));
    }
}
