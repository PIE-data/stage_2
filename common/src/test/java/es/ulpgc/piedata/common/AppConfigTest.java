package es.ulpgc.piedata.common;

import es.ulpgc.piedata.common.config.AppConfig;
import es.ulpgc.piedata.common.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private static final String DEFAULTS = "test-defaults.properties";

    @Test
    void readsDefaults() {
        AppConfig c = AppConfig.load(DEFAULTS, Map.of(), new Properties());
        assertEquals("test-service", c.get("service.name"));
        assertEquals(8, c.getInt("server.threads.max"));
    }

    @Test
    void layersOverrideInOrder(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("replica.properties");
        Files.writeString(file, "server.port=7100\nserver.threads.max=16\n");
        Properties sys = new Properties();
        sys.setProperty("server.port", "7300");

        AppConfig c = AppConfig.load(DEFAULTS,
                Map.of("CONFIG_FILE", file.toString(), "SERVER_PORT", "7200", "SERVER_THREADS_MIN", "4"),
                sys);

        assertEquals(7300, c.getInt("server.port"), "system property wins over env and file");
        assertEquals(4, c.getInt("server.threads.min"), "env wins over defaults");
        assertEquals(16, c.getInt("server.threads.max"), "external file wins over defaults");
    }

    @Test
    void unknownEnvironmentKeysAreIgnored() {
        AppConfig c = AppConfig.load(DEFAULTS, Map.of("SERVER_PROT", "1"), new Properties());
        assertThrows(ConfigException.class, () -> c.get("server.prot"));
    }

    @Test
    void envNameConvention() {
        assertEquals("SERVER_PORT", AppConfig.envName("server.port"));
        assertEquals("BROKER_DATA_DIR", AppConfig.envName("broker.data-dir"));
    }

    @Test
    void badIntegerFailsClearly() {
        AppConfig c = AppConfig.of(Map.of("server.port", "abc"));
        ConfigException e = assertThrows(ConfigException.class, () -> c.getInt("server.port"));
        assertTrue(e.getMessage().contains("server.port"));
    }

    @Test
    void missingResourceFailsClearly() {
        assertThrows(ConfigException.class, () -> AppConfig.load("nope.properties", Map.of(), new Properties()));
    }
}
