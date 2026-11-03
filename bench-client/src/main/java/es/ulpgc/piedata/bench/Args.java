package es.ulpgc.piedata.bench;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Command-line options of the form {@code --name value}; every option read is recorded. */
final class Args {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> used = new LinkedHashMap<>();

    Args(String[] argv, int from) {
        for (int i = from; i < argv.length; i++) {
            String a = argv[i];
            if (!a.startsWith("--") || i + 1 >= argv.length) {
                throw new IllegalArgumentException("expected --name value, got '" + a + "'");
            }
            values.put(a.substring(2), argv[++i]);
        }
    }

    String get(String name) {
        String v = values.get(name);
        if (v == null) {
            throw new IllegalArgumentException("missing option --" + name);
        }
        used.put(name, v);
        return v;
    }

    String get(String name, String def) {
        String v = values.getOrDefault(name, def);
        if (v != null) {
            used.put(name, v);
        }
        return v;
    }

    int getInt(String name, int def) {
        return Integer.parseInt(get(name, String.valueOf(def)));
    }

    double getDouble(String name, double def) {
        return Double.parseDouble(get(name, String.valueOf(def)));
    }

    Path getPath(String name) {
        return Path.of(get(name));
    }

    /** Fails on options that were given but never read (typos). */
    void rejectUnknown() {
        Set<String> unknown = new java.util.TreeSet<>(values.keySet());
        unknown.removeAll(used.keySet());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown option(s): --" + String.join(", --", unknown));
        }
    }

    /** The options actually used, defaults included: written into every summary. */
    Map<String, String> used() {
        return used;
    }
}
