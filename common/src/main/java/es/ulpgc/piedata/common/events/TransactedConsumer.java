package es.ulpgc.piedata.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransactedConsumer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(TransactedConsumer.class);

    @FunctionalInterface
    public interface MessageHandler {
        void handle(Session session, TextMessage message) throws Exception;
    }

    private final BrokerClient brokerClient;
    private final String destinationName;
    private final boolean isTopic;
    private final int threadCount;
    private final MessageHandler handler;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TransactedConsumer(
            BrokerClient brokerClient,
            String destinationName,
            boolean isTopic,
            int threadCount,
            MessageHandler handler
    ) {
        this.brokerClient = brokerClient;
        this.destinationName = destinationName;
        this.isTopic = isTopic;
        this.threadCount = threadCount;
        this.handler = handler;
        this.executor = Executors.newFixedThreadPool(threadCount);
    }

    public synchronized void start() {
        if (running.compareAndSet(false, true)) {
            for (int i = 0; i < threadCount; i++) {
                executor.submit(this::consumeLoop);
            }
        }
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                Connection conn = brokerClient.getConnection();
                try (Session session = conn.createSession(true, Session.SESSION_TRANSACTED)) {
                    Destination destination = isTopic ? session.createTopic(destinationName) : session.createQueue(destinationName);
                    try (MessageConsumer consumer = session.createConsumer(destination)) {
                        while (running.get()) {
                            Message message = consumer.receive(1000);
                            if (message == null) {
                                continue;
                            }
                            if (message instanceof TextMessage textMessage) {
                                try {
                                    handler.handle(session, textMessage);
                                    session.commit();
                                } catch (Exception e) {
                                    LOG.warn("Error processing message from destination {}; rolling back session for redelivery: {}",
                                            destinationName, e.getMessage());
                                    session.rollback();
                                }
                            } else {
                                LOG.error("Unexpected non-text message type on destination {}; rolling back session", destinationName);
                                session.rollback();
                            }
                        }
                    }
                }
            } catch (JMSException e) {
                if (running.get()) {
                    LOG.warn("JMS connection issue on destination {}; reconnecting: {}", destinationName, e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            } catch (Exception e) {
                LOG.error("Unexpected error in consumer loop on destination {}", destinationName, e);
            }
        }
    }

    @Override
    public synchronized void close() {
        running.set(false);
        executor.shutdownNow();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
