package es.ulpgc.piedata.common.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class IdempotentConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(IdempotentConsumer.class);

    @FunctionalInterface
    public interface WorkAction {
        void execute() throws Exception;
    }

    @FunctionalInterface
    public interface DuplicateAction {
        void onDuplicate() throws Exception;
    }

    private IdempotentConsumer() {
    }

    public static boolean execute(
            long bookId,
            boolean alreadyCompleted,
            WorkAction work,
            DuplicateAction onDuplicate
    ) throws Exception {
        if (alreadyCompleted) {
            LOG.info("Skipping already completed work for book_id={}", bookId);
            if (onDuplicate != null) {
                onDuplicate.onDuplicate();
            }
            return false;
        }
        work.execute();
        return true;
    }
}