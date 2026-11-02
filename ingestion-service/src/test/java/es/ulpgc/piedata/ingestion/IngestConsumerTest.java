package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import es.ulpgc.piedata.common.events.IngestRequested;
import es.ulpgc.piedata.ingestion.split.BookSplitter;
import io.javalin.Javalin;
import org.apache.activemq.broker.BrokerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IngestConsumerTest {

    private BrokerService broker;
    private String brokerUrl;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        broker = new BrokerService();
        broker.setPersistent(false);
        broker.setUseJmx(false);
        broker.setDataDirectoryFile(tempDir.resolve("broker").toFile());
        broker.addConnector("tcp://127.0.0.1:0");
        broker.start();
        broker.waitUntilStarted();
        brokerUrl = broker.getTransportConnectors().get(0).getConnectUri().toString();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
        }
    }

    @Test
    void consumesIngestRequestAndPublishesBookDownloaded(@TempDir Path dataDir) throws Exception {
        String bookText = "Title: ActiveMQ Book\nAuthor: Worker\n\n" +
                BookSplitter.START_MARKER + " BOOK ***\n" +
                "The story body\n" +
                BookSplitter.END_MARKER + " BOOK ***\n";

        Javalin mockHttp = Javalin.create().start(0);
        mockHttp.get("/cache/epub/777/pg777.txt", ctx -> ctx.result(bookText));

        try (BrokerClient brokerClient = new BrokerClient(brokerUrl)) {
            IngestionService ingestionService = new IngestionService(dataDir, "http://localhost:" + mockHttp.port());
            IngestConsumer consumer = new IngestConsumer(brokerClient, ingestionService, 2);
            consumer.start();

            EventPublisher publisher = new EventPublisher(brokerClient);
            publisher.publish(Destinations.INGEST_REQUESTS, false, IngestRequested.of(777L), 777L);

            AtomicReference<BookDownloaded> received = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            Connection conn = brokerClient.getConnection();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue downloadedQueue = session.createQueue(Destinations.BOOKS_DOWNLOADED);
            MessageConsumer downloadedConsumer = session.createConsumer(downloadedQueue);

            Thread listenerThread = new Thread(() -> {
                try {
                    TextMessage msg = (TextMessage) downloadedConsumer.receive(5000);
                    if (msg != null) {
                        received.set(EventParser.parse(msg.getText(), BookDownloaded.class, BookDownloaded.EVENT_NAME));
                        latch.countDown();
                    }
                } catch (Exception ignored) {
                }
            });
            listenerThread.start();

            assertTrue(latch.await(6, TimeUnit.SECONDS), "Timed out waiting for BookDownloaded event");
            assertNotNull(received.get());
            assertEquals(777L, received.get().book_id());
            assertTrue(Files.exists(dataDir.resolve(received.get().header_path())));
            assertTrue(Files.exists(dataDir.resolve(received.get().body_path())));

            consumer.close();
            downloadedConsumer.close();
            session.close();
        } finally {
            mockHttp.stop();
        }
    }
}