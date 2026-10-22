package es.ulpgc.piedata.common.config;

/** Wrong or missing configuration: the service must not start. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
