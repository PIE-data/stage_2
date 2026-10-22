package es.ulpgc.piedata.common.json;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** The single Gson instance shared by every service (Gson is thread-safe). */
public final class Json {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    public static String write(Object value) {
        return GSON.toJson(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return GSON.fromJson(json, type);
    }
}
