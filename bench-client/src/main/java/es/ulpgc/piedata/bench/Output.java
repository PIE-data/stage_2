package es.ulpgc.piedata.bench;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result files of one run: {@code summary.json} plus CSV files, all in one folder. */
final class Output {

    static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().serializeNulls()
            .disableHtmlEscaping().serializeSpecialFloatingPointValues().create();

    private final Path dir;

    Output(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Path dir() {
        return dir;
    }

    void csv(String name, String header, List<String> rows) {
        StringBuilder sb = new StringBuilder(header).append('\n');
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        write(name, sb.toString());
    }

    void json(String name, Object value) {
        write(name, PRETTY.toJson(value) + "\n");
    }

    void write(String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Common head of every summary: what was run, when, and on which client host. */
    static Map<String, Object> head(String kind, Args args) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("label", args.get("label", kind));
        m.put("started_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        m.put("options", args.used());
        Map<String, Object> host = new LinkedHashMap<>();
        host.put("cpus", Runtime.getRuntime().availableProcessors());
        host.put("client_max_heap_mb", Runtime.getRuntime().maxMemory() / 1024 / 1024);
        host.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        host.put("java", System.getProperty("java.runtime.version"));
        m.put("client_host", host);
        return m;
    }
}
