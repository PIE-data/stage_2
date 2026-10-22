package es.ulpgc.piedata.common.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Configuration of one service process.
 *
 * <p>Values are read in four layers; a later layer overrides an earlier one:
 * <ol>
 *   <li>defaults: {@code application.properties} inside the service jar;</li>
 *   <li>an optional external file named by the environment variable {@code CONFIG_FILE};</li>
 *   <li>environment variables: key {@code server.port} is overridden by {@code SERVER_PORT}
 *       (upper case, dots and dashes become underscores);</li>
 *   <li>JVM system properties: {@code -Dserver.port=7013}.</li>
 * </ol>
 * Only keys present in the defaults can be overridden by layers 3 and 4, so a typo in an
 * environment variable never silently creates a new setting.
 *
 * <p>This is what lets several replicas of the same jar run on one machine:
 * {@code SERVER_PORT=7013 java -jar search-service.jar}.
 */
public final class AppConfig {

    public static final String CONFIG_FILE_ENV = "CONFIG_FILE";

    private final Properties values;

    private AppConfig(Properties values) {
        this.values = values;
    }

    /** Loads the configuration from the real environment. */
    public static AppConfig load(String defaultsResource) {
        return load(defaultsResource, System.getenv(), System.getProperties());
    }

    /** Loads the configuration from explicit sources (used by tests). */
    public static AppConfig load(String defaultsResource, Map<String, String> env, Properties sysProps) {
        Properties p = readResource(defaultsResource);

        String external = env.get(CONFIG_FILE_ENV);
        if (external != null && !external.isBlank()) {
            p.putAll(readFile(Path.of(external)));
        }
        for (String key : p.stringPropertyNames()) {
            String fromEnv = env.get(envName(key));
            if (fromEnv != null) {
                p.setProperty(key, fromEnv);
            }
        }
        for (String key : p.stringPropertyNames()) {
            String fromSys = sysProps.getProperty(key);
            if (fromSys != null) {
                p.setProperty(key, fromSys);
            }
        }
        return new AppConfig(p);
    }

    /** Builds a configuration from fixed values (used by tests). */
    public static AppConfig of(Map<String, String> entries) {
        Properties p = new Properties();
        p.putAll(entries);
        return new AppConfig(p);
    }

    /** {@code server.port} -> {@code SERVER_PORT}. */
    public static String envName(String key) {
        return key.toUpperCase().replace('.', '_').replace('-', '_');
    }

    public String get(String key) {
        String v = values.getProperty(key);
        if (v == null) {
            throw new ConfigException("missing configuration key '" + key + "'");
        }
        return v.trim();
    }

    public String get(String key, String defaultValue) {
        String v = values.getProperty(key);
        return v == null ? defaultValue : v.trim();
    }

    public int getInt(String key) {
        String v = get(key);
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new ConfigException("configuration key '" + key + "' must be an integer, got '" + v + "'");
        }
    }

    public int getInt(String key, int defaultValue) {
        return values.getProperty(key) == null ? defaultValue : getInt(key);
    }

    public Path getPath(String key) {
        return Path.of(get(key));
    }

    /** A copy of every value, for logging at startup. */
    public Map<String, String> asMap() {
        Map<String, String> out = new java.util.TreeMap<>();
        for (String key : values.stringPropertyNames()) {
            out.put(key, values.getProperty(key));
        }
        return out;
    }

    private static Properties readResource(String name) {
        Properties p = new Properties();
        try (InputStream in = AppConfig.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new ConfigException("configuration resource '" + name + "' not found on the classpath");
            }
            try (Reader r = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        } catch (IOException e) {
            throw new ConfigException("cannot read configuration resource '" + name + "'", e);
        }
        return p;
    }

    private static Properties readFile(Path file) {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new ConfigException("cannot read configuration file '" + file + "'", e);
        }
        return p;
    }
}
