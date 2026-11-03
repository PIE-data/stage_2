package es.ulpgc.piedata.broker;

import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import es.ulpgc.piedata.common.events.IdempotentConsumer;
import es.ulpgc.piedata.common.events.TransactedConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventContractChecksTest {

    private static EmbeddedBroker broker;
    private static BrokerClient client;
    private static EventPublisher publisher;

    @BeforeAll
    static void setup(@TempDir Path tempDir) throws Exception {
        broker = new EmbeddedBroker(0, tempDir.resolve("broker_data").toString(), 50L, 5);
        broker.start();

        client = new BrokerClient("tcp://localhost:" + broker.getPort(), 50L, 5);
        publisher = new EventPublisher(client);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    void check1_sameEventDeliveredTwiceProducesOneEffect() throws Exception {
        String queue = "test.idempotent";
        Set<Long> processedBookIds = ConcurrentHashMap.newKeySet();
        AtomicInteger actualWorkExecutions = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(2);

        try (TransactedConsumer consumer = new TransactedConsumer(client, queue, false, 1, (session, msg) -> {
            BookDownloaded ev = EventParser.parse(msg.getText(), BookDownloaded.class, BookDownloaded.EVENT_NAME);
            IdempotentConsumer.execute(
                    ev.book_id(),
                    processedBookIds.contains(ev.book_id()),
                    () -> {
                        actualWorkExecutions.incrementAndGet();
                        processedBookIds.add(ev.book_id());
                    },
                    () -> {}
            );
            latch.countDown();
        })) {
            consumer.start();

            BookDownloaded event = BookDownloaded.of(1342, "00/13/1342.header.txt", "00/13/1342.body.txt", "2026-10-22T10:00:03Z");
            publisher.publish(queue, false, event, 1342);
            publisher.publish(queue, false, event, 1342);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, actualWorkExecutions.get());
        }
    }

    @Test
    void check2_consumerKilledBeforeAckCausesRedelivery() throws Exception {
        String queue = "test.redelivery";
        AtomicInteger attempts = new AtomicInteger(0);
        CountDownLatch deliveredLatch = new CountDownLatch(2);

        try (TransactedConsumer consumer = new TransactedConsumer(client, queue, false, 1, (session, msg) -> {
            int attempt = attempts.incrementAndGet();
            deliveredLatch.countDown();
            if (attempt == 1) {
                throw new RuntimeException("Simulated worker failure before ack");
            }
        })) {
            consumer.start();

            BookDownloaded event = BookDownloaded.of(2001, "header", "body", "2026-10-22T10:00:00Z");
            publisher.publish(queue, false, event, 2001);

            assertTrue(deliveredLatch.await(5, TimeUnit.SECONDS));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    void check3_poisonMessageEndsInDLQ() throws Exception {
        String queue = "test.poison";
        try (TransactedConsumer consumer = new TransactedConsumer(client, queue, false, 1, (session, msg) ->
                EventParser.parse(msg.getText(), BookDownloaded.class, BookDownloaded.EVENT_NAME))) {
            consumer.start();

            Connection conn = client.getConnection();
            try (Session s = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                TextMessage badMsg = s.createTextMessage("{\"event\":\"BadEvent\",\"book_id\":-1}");
                badMsg.setLongProperty("book_id", -1L);
                s.createProducer(s.createQueue(queue)).send(badMsg);
            }

            try (Session dlqSession = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Queue dlqQueue = dlqSession.createQueue(Destinations.DLQ);
                try (MessageConsumer dlqConsumer = dlqSession.createConsumer(dlqQueue)) {
                    Message deadLetter = dlqConsumer.receive(6000);
                    assertNotNull(deadLetter);
                    assertTrue(((TextMessage) deadLetter).getText().contains("BadEvent"));
                }
            }
        }
    }
}