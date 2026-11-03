package es.ulpgc.piedata.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Like {@link TransactedConsumer}, but each transaction holds a BATCH of messages from a queue:
 * up to {@code maxBatch} messages, or fewer if no new message arrives within {@code maxWaitMs} of
 * the first one. The handler processes the whole batch, then the session is committed (all the
 * messages acknowledged and everything the handler sent is delivered, atomically); on any error
 * the session is rolled back and every message of the batch is redelivered.
 *
 * <p>Poison messages: after a failed batch, the next messages of that size (the ones being
 * redelivered) and any message already marked as redelivered are processed ONE per transaction.
 * So a single bad message ends alone in {@code ActiveMQ.DLQ} after the redeliveries, instead of
 * dragging the good messages of its batch with it.
 */
public final class BatchTransactedConsumer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BatchTransactedConsumer.class);

    /** Processes one batch; throwing rolls back the whole batch. */
    @FunctionalInterface
    public interface BatchHandler {
        void handle(Session session, List<TextMessage> batch) throws Exception;
    }

    /** Counters since start, for logs and status endpoints. */
    public record Stats(long batches, long messages, long rollbacks) {
    }

    private final BrokerClient brokerClient;
    private final String queueName;
    private final int threadCount;
    private final int maxBatch;
    private final long maxWaitMs;
    private final BatchHandler handler;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong messages = new AtomicLong();
    private final AtomicLong rollbacks = new AtomicLong();

    public BatchTransactedConsumer(BrokerClient brokerClient, String queueName, int threadCount,
                                   int maxBatch, long maxWaitMs, BatchHandler handler) {
        if (threadCount < 1 || maxBatch < 1 || maxWaitMs < 0) {
            throw new IllegalArgumentException("threads and batch size must be >= 1, wait >= 0");
        }
        this.brokerClient = brokerClient;
        this.queueName = queueName;
        this.threadCount = threadCount;
        this.maxBatch = maxBatch;
        this.maxWaitMs = maxWaitMs;
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

    public Stats stats() {
        return new Stats(batches.get(), messages.get(), rollbacks.get());
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                Connection conn = brokerClient.getConnection();
                try (Session session = conn.createSession(true, Session.SESSION_TRANSACTED);
                     MessageConsumer consumer = session.createConsumer(session.createQueue(queueName))) {
                    int isolate = 0;   // messages still to process one by one after a failed batch
                    while (running.get()) {
                        List<Message> batch = collect(consumer, isolate > 0);
                        if (batch.isEmpty()) {
                            continue;
                        }
                        isolate = Math.max(0, isolate - batch.size());
                        try {
                            handler.handle(session, asText(batch));
                            session.commit();
                            batches.incrementAndGet();
                            messages.addAndGet(batch.size());
                        } catch (Exception e) {
                            LOG.warn("Batch of {} message(s) from {} failed, rolling back for redelivery: {}",
                                    batch.size(), queueName, e.toString());
                            session.rollback();
                            rollbacks.incrementAndGet();
                            if (batch.size() > 1) {
                                isolate = batch.size();
                            }
                        }
                    }
                }
            } catch (JMSException e) {
                if (running.get()) {
                    LOG.warn("JMS connection issue on {}; reconnecting: {}", queueName, e.getMessage());
                    pause();
                }
            } catch (Exception e) {
                LOG.error("Unexpected error in batch consumer loop on {}", queueName, e);
                pause();
            }
        }
    }

    /**
     * Waits up to 1 s for a first message, then keeps receiving until the batch is full or
     * {@code maxWaitMs} have passed since the first one. A single message when {@code single} or
     * when the first message is a redelivery.
     */
    private List<Message> collect(MessageConsumer consumer, boolean single) throws JMSException {
        List<Message> batch = new ArrayList<>();
        Message first = consumer.receive(1000);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        if (single || first.getJMSRedelivered()) {
            return batch;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        while (batch.size() < maxBatch && running.get()) {
            long leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            Message next = leftMs > 0 ? consumer.receive(leftMs) : consumer.receiveNoWait();
            if (next == null) {
                break;
            }
            batch.add(next);
        }
        return batch;
    }

    /** Non-text messages are malformed: failing the batch sends them, alone, towards the DLQ. */
    private List<TextMessage> asText(List<Message> batch) {
        List<TextMessage> texts = new ArrayList<>(batch.size());
        for (Message m : batch) {
            if (!(m instanceof TextMessage t)) {
                throw new IllegalArgumentException("non-text message on " + queueName + ": " + m.getClass().getName());
            }
            texts.add(t);
        }
        return texts;
    }

    private static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops receiving; a batch not yet committed is rolled back by the broker and redelivered. */
    @Override
    public synchronized void close() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
