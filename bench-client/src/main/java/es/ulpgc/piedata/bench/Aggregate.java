package es.ulpgc.piedata.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Repeated runs → one table: finds every {@code summary.json} under {@code --runs}, groups them
 * by {@code label}, and for each numeric metric writes mean, standard deviation, min and max over
 * the runs (brief section 6.5: averages and standard deviations for repeated measurements).
 */
final class Aggregate {

    /** Fields that describe the run, not a measurement. */
    private static final Set<String> SKIP = Set.of("options", "client_host", "started_at", "kind", "label");

    private Aggregate() {
    }

    static Map<String, Object> run(Args args) throws IOException {
        Path root = args.getPath("runs");
        Path out = args.getPath("out");
        args.rejectUnknown();

        // label -> metric -> one value per run
        Map<String, Map<String, List<Double>>> byLabel = new TreeMap<>();
        Map<String, String> kinds = new TreeMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) {
            files = s.filter(p -> p.getFileName().toString().equals("summary.json")).sorted().toList();
        }
        for (Path f : files) {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            String label = o.get("label").getAsString();
            kinds.put(label, o.get("kind").getAsString());
            Map<String, List<Double>> metrics = byLabel.computeIfAbsent(label, k -> new TreeMap<>());
            flatten("", o, metrics);
        }
        List<String> rows = new ArrayList<>();
        for (var e : byLabel.entrySet()) {
            for (var m : e.getValue().entrySet()) {
                Stats s = Stats.of(m.getValue().stream().mapToDouble(Double::doubleValue).toArray());
                rows.add(e.getKey() + "," + kinds.get(e.getKey()) + "," + s.n() + "," + m.getKey() + ","
                        + f(s.mean()) + "," + f(s.sd()) + "," + f(s.min()) + "," + f(s.max()));
            }
        }
        StringBuilder sb = new StringBuilder("label,kind,runs,metric,mean,sd,min,max\n");
        rows.forEach(r -> sb.append(r).append('\n'));
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
        return Map.of("summaries", files.size(), "labels", byLabel.size(), "out", out.toString());
    }

    static void flatten(String prefix, JsonObject o, Map<String, List<Double>> into) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (prefix.isEmpty() && SKIP.contains(e.getKey())) {
                continue;
            }
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonElement v = e.getValue();
            if (v.isJsonObject()) {
                flatten(key, v.getAsJsonObject(), into);
            } else if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                into.computeIfAbsent(key, k -> new ArrayList<>()).add(v.getAsDouble());
            }
        }
    }

    private static String f(double x) {
        return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.3f", x);
    }
}
