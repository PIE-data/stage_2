package es.ulpgc.piedata.ingestion;

import es.ulpgc.piedata.common.datalake.Receipt;
import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import es.ulpgc.piedata.common.events.IngestRequested;
import es.ulpgc.piedata.common.events.TransactedConsumer;
import es.ulpgc.piedata.ingestion.download.BookNotFoundException;
import es.ulpgc.piedata.ingestion.split.MarkersNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Destination;
import javax.jms.Session;
import javax.jms.TextMessage;

public final class IngestConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestConsumer.class);

    private final IngestionService ingestionService;
    private final TransactedConsumer consumer;

    public IngestConsumer(BrokerClient brokerClient, IngestionService ingestionService, int threads) {
        this.ingestionService = ingestionService;
        this.consumer = new TransactedConsumer(
                brokerClient,
                Destinations.INGEST_REQUESTS,
                false,
                threads,
                this::handleMessage
        );
    }

    public void start() {
        consumer.start();
    }

    private void handleMessage(Session session, TextMessage message) throws Exception {
        IngestRequested request = EventParser.parse(message.getText(), IngestRequested.class, IngestRequested.EVENT_NAME);
        long bookId = request.book_id();
        try {
            IngestionService.IngestResult result = ingestionService.ingest(bookId);
            Receipt receipt = result.receipt();
            BookDownloaded downloadedEvent = BookDownloaded.of(
                    receipt.bookId(),
                    receipt.headerPath(),
                    receipt.bodyPath(),
                    receipt.ingestedAt()
            );
            Destination targetQueue = session.createQueue(Destinations.BOOKS_DOWNLOADED);
            EventPublisher.publishInSession(session, targetQueue, downloadedEvent, receipt.bookId());
        } catch (BookNotFoundException | MarkersNotFoundException e) {
            LOG.warn("Permanent failure on book_id={}, message acknowledged to prevent retry: {}", bookId, e.getMessage());
        }
    }

    @Override
    public void close() {
        consumer.close();
    }
}