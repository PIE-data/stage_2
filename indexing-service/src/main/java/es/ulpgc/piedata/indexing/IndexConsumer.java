package es.ulpgc.piedata.indexing;

import es.ulpgc.piedata.common.events.BatchTransactedConsumer;
import es.ulpgc.piedata.common.events.BookDownloaded;
import es.ulpgc.piedata.common.events.BookIndexed;
import es.ulpgc.piedata.common.events.BrokerClient;
import es.ulpgc.piedata.common.events.Destinations;
import es.ulpgc.piedata.common.events.EventParser;
import es.ulpgc.piedata.common.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Destination;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Piece D of #4: consumes {@code books.downloaded} in batches (docs/EVENTS.md section 5).
 *
 * <p>For each batch, in one transacted JMS session: parse every message, index all the books with
 * {@link IndexingService#updateAll} (ONE datamart transaction), then send one {@code BookIndexed}
 * per book to the {@code books.indexed} topic and commit the session. The datamart commits BEFORE
 * the session, so a book is announced only when it is searchable. If anything fails, the session
 * is rolled back and the messages are redelivered (5 times, then {@code ActiveMQ.DLQ}).
 *
 * <p>Duplicates are harmless: the same book twice in a batch is indexed once; a book already
 * indexed is re-indexed, which replaces its rows with identical ones (docs/DATAMART.md section 3),
 * and {@code BookIndexed} is sent again (subscribers count distinct ids).
 */
public final class IndexConsumer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IndexConsumer.class);

    private final IndexingService service;
    private final BatchTransactedConsumer consumer;

    public IndexConsumer(BrokerClient brokerClient, IndexingService service,
                         int threads, int batchSize, long batchWaitMs) {
        this.service = service;
        this.consumer = new BatchTransactedConsumer(brokerClient, Destinations.BOOKS_DOWNLOADED,
                threads, batchSize, batchWaitMs, this::handle);
    }

    public void start() {
        consumer.start();
    }

    public BatchTransactedConsumer.Stats stats() {
        return consumer.stats();
    }

    private void handle(Session session, List<TextMessage> batch) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        for (TextMessage message : batch) {
            ids.add(EventParser.parse(message.getText(), BookDownloaded.class, BookDownloaded.EVENT_NAME).book_id());
        }
        long start = System.nanoTime();
        service.updateAll(new ArrayList<>(ids));           // datamart COMMIT happens here
        Destination indexed = session.createTopic(Destinations.BOOKS_INDEXED);
        for (long id : ids) {
            EventPublisher.publishInSession(session, indexed, BookIndexed.of(id), id);
        }
        LOG.info("Indexed {} book(s) from {} message(s) in {} ms", ids.size(), batch.size(),
                (System.nanoTime() - start) / 1_000_000);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
