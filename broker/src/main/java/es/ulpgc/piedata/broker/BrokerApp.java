package es.ulpgc.piedata.broker;

import es.ulpgc.piedata.common.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Message broker process: an embedded ActiveMQ broker started as its own jar, so no Docker or
 * separate installation is needed on the Stage 2 machine.
 *
 * <p>Skeleton only: the broker itself, its persistence, redelivery policy and dead-letter queue
 * are implemented in issue #2 according to {@code docs/EVENTS.md}.
 */
public final class BrokerApp {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerApp.class);

    private BrokerApp() {
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.load("application.properties");
        LOG.error("broker not implemented yet (issue #2); configuration would be {}", config.asMap());
        System.exit(1);
    }
}
