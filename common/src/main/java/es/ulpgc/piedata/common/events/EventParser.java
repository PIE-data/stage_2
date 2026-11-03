package es.ulpgc.piedata.common.events;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import es.ulpgc.piedata.common.json.Json;

public final class EventParser {
    private EventParser() {
    }

    public static <T> T parse(String json, Class<T> clazz, String expectedEvent) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Payload is a empty message or null.");
        }

        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (Exception e){
            throw new IllegalArgumentException("Corrupted JSON: " + json, e);
        }

        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Message must be a JSON object " + json);
        }

        JsonObject obj = element.getAsJsonObject();

        if (!obj.has("event") || obj.get("event").isJsonNull()) {
            throw new IllegalArgumentException("Message without 'event': " + json);
        }
        String actualEvent = obj.get("event").getAsString();
        if (!expectedEvent.equals(actualEvent)) {
            throw new IllegalArgumentException("Unexpected event, it was expected: '" + expectedEvent
                    + "' but received '" + actualEvent + "'");
        }
        if (!obj.has("book_id") || obj.get("book_id").isJsonNull()) {
            throw new IllegalArgumentException("Message without 'book_id': " + json);
        }
        long bookId;
        try {
            bookId = obj.get("book_id").getAsLong();
        } catch (Exception e) {
            throw new IllegalArgumentException("'book_id' has an illegal value: " + json, e);
        }
        if (bookId <= 0) {
            throw new IllegalArgumentException("book_id must be greater than 0, received: " + bookId);
        }
        return Json.read(json, clazz);
    }
}
