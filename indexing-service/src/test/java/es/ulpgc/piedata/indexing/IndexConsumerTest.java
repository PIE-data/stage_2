package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.datalake.HashLayout;
import es.ulpgc.piedata.common.datamart.Datamart;
import es.ulpgc.piedata.common.events.BatchTransactedConsumer;
import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BookIndexed;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Piece D: the batch consumer of books.downloaded against an in-memory broker. */
class IndexConsumerTest {

    @TempDir
    Path dataDir;

    private BrokerService broker;
    private BrokerClient client;
    private IndexingService service;
    private IndexConsumer consumer;
    private Connection listenerConnection;
    private final Set<Long> indexedEvents = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(dataDir.resolve("broker").toFile());
        broker.addConnector("tcp://127.0.0.1:0");
        broker.start();
        broker.waitUntilStarted();
        String url = broker.getTransportConnectors().get(0).getConnectUri().toString();
        client = new BrokerClient(url, 10, 5);        // short redelivery delays: test stays fast
        service = new IndexingService(dataDir);

        // books.indexed is a topic: subscribe BEFORE anything is published
        listenerConnection = client.getFactory().createConnection();
        listenerConnection.start();
        Session s = listenerConnection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        s.createConsumer(s.createTopic(Destinations.BOOKS_INDEXED)).setMessageListener(m -> {
            try {
                indexedEvents.add(EventParser.parse(((TextMessage) m).getText(), BookIndexed.class,
                        BookIndexed.EVENT_NAME).book_id());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        listenerConnection.close();
        client.close();
        service.close();
        broker.stop();
        broker.waitUntilStopped();
    }

    private void startConsumer(int batchSize) {
        consumer = new IndexConsumer(client, service, 1, batchSize, 500);
        consumer.start();
    }

    private void sendDownloaded(long id) throws Exception {
        new EventPublisher(client).publish(Destinations.BOOKS_DOWNLOADED, false,
                BookDownloaded.of(id, HashLayout.headerPath(id), HashLayout.bodyPath(id), "2026-10-01T00:00:00Z"), id);
    }

    private void sendRaw(String body) throws Exception {
        Connection c = client.getConnection();
        try (Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageProducer p = s.createProducer(s.createQueue(Destinations.BOOKS_DOWNLOADED))) {
            p.send(s.createTextMessage(body));
        }
    }

    private void awaitIndexedEvents(int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (indexedEvents.size() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(expected, indexedEvents.size(), "BookIndexed events received: " + indexedEvents);
    }

    private String exportedSha256() throws Exception {
        try (Datamart d = Datamart.open(Datamart.defaultFile(dataDir))) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d.exportCanonical()));
        }
    }

    @Test
    void indexesInBatchesWithDuplicatesAndAnnouncesEachBook() throws Exception {
        List<Long> ids = TestWorkspace.ingestGolden(dataDir);
        for (long id : ids) {
            sendDownloaded(id);
        }
        for (long id : ids.subList(0, 5)) {
            sendDownloaded(id);                       // at-least-once: the same book again
        }
        startConsumer(8);                             // 25 messages already waiting

        awaitIndexedEvents(ids.size());
        assertEquals(Set.copyOf(ids), indexedEvents);
        awaitConsumed(ids.size() + 5);
        BatchTransactedConsumer.Stats stats = consumer.stats();
        assertTrue(stats.batches() < stats.messages(), "messages were not grouped: " + stats);
        assertEquals(0, stats.rollbacks());
        assertEquals(ids.size(), service.status().booksIndexed());
        assertEquals(TestWorkspace.expectedCanonicalSha256(), exportedSha256(),
                "duplicates must leave the same datamart as indexing each book once");
    }

    @Test
    void badMessagesEndAloneInDlqAndGoodOnesAreIndexed() throws Exception {
        List<Long> ids = TestWorkspace.goldenIds().subList(0, 3);
        for (long id : ids) {
            TestWorkspace.ingest(dataDir, id);
        }
        sendDownloaded(ids.get(0));
        sendRaw("{not json");                         // malformed
        sendDownloaded(ids.get(1));
        sendDownloaded(999_999L);                     // no receipt: updateAll fails
        sendDownloaded(ids.get(2));
        startConsumer(10);                            // all five in the first batch -> it fails

        awaitIndexedEvents(3);
        assertEquals(Set.copyOf(ids), indexedEvents);
        assertEquals(3, service.status().booksIndexed());

        // after 5 redeliveries each bad message is in the DLQ, the good ones are not
        Connection c = client.getConnection();
        try (Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
             MessageConsumer dlq = s.createConsumer(s.createQueue(Destinations.DLQ))) {
            TextMessage first = (TextMessage) dlq.receive(15_000);
            TextMessage second = (TextMessage) dlq.receive(15_000);
            assertNotNull(first, "malformed / not-ingested message not in DLQ");
            assertNotNull(second, "malformed / not-ingested message not in DLQ");
            assertEquals(Set.of("{not json", "999999"), Set.of(dlqKey(first), dlqKey(second)));
            assertNull(dlq.receive(500), "a good message ended in the DLQ");
        }
        assertTrue(consumer.stats().rollbacks() >= 1);
    }

    private static String dlqKey(TextMessage m) throws Exception {
        return m.propertyExists("book_id") ? String.valueOf(m.getLongProperty("book_id")) : m.getText();
    }

    private void awaitConsumed(long messages) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (consumer.stats().messages() < messages && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(messages, consumer.stats().messages(), "messages committed");
    }
}
