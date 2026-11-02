package es.ulpgc.piedata.broker;

import org.apache.activemq.broker.BrokerService;
import org.apache.activemq.broker.region.Destination;
import org.apache.activemq.broker.region.DestinationStatistics;
import org.apache.activemq.broker.region.Queue;
import org.apache.activemq.broker.region.policy.PolicyEntry;
import org.apache.activemq.broker.region.policy.PolicyMap;
import org.apache.activemq.broker.region.policy.SharedDeadLetterStrategy;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.store.kahadb.KahaDBPersistenceAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class EmbeddedBroker implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedBroker.class);

    private final BrokerService broker;
    private final int port;
    private final String dataDir;

    public EmbeddedBroker(int port, String dataDir) throws Exception {
        this(port, dataDir, 1000L, 5);
    }

    public EmbeddedBroker(int port, String dataDir, long initialRedeliveryDelayMs, int maxRedeliveries) throws Exception {
        this.port = port;
        this.dataDir = dataDir;
        this.broker = new BrokerService();
        this.broker.setBrokerName("pie-broker");
        this.broker.setUseJmx(false);

        KahaDBPersistenceAdapter adapter = new KahaDBPersistenceAdapter();
        adapter.setDirectory(new File(dataDir));
        broker.setPersistenceAdapter(adapter);
        broker.setPersistent(true);

        PolicyMap policyMap = new PolicyMap();
        PolicyEntry defaultEntry = new PolicyEntry();
        SharedDeadLetterStrategy dlq = new SharedDeadLetterStrategy();
        dlq.setDeadLetterQueue(new ActiveMQQueue("ActiveMQ.DLQ"));
        defaultEntry.setDeadLetterStrategy(dlq);
        defaultEntry.setQueuePrefetch(1);
        defaultEntry.setTopicPrefetch(1);
        defaultEntry.setEnableAudit(true);
        policyMap.setDefaultEntry(defaultEntry);
        broker.setDestinationPolicy(policyMap);

        broker.addConnector("tcp://0.0.0.0:" + port);
    }

    public void start() throws Exception {
        broker.start();
        broker.waitUntilStarted();
        LOG.info("ActiveMQ broker started on tcp://0.0.0.0:{} (KahaDB: {})", port, dataDir);
    }

    public int getPort() {
        if (port == 0 && broker.getTransportConnectors() != null && !broker.getTransportConnectors().isEmpty()) {
            try {
                return broker.getTransportConnectors().get(0).getConnectUri().getPort();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to resolve dynamic broker port", e);
            }
        }
        return port;
    }

    public boolean isStarted() {
        return broker.isStarted();
    }

    public BrokerStats getQueueStats(String queueName) {
        try {
            Destination dest = broker.getRegionBroker().getDestinationMap().get(new ActiveMQQueue(queueName));
            if (dest instanceof Queue q) {
                DestinationStatistics stats = q.getDestinationStatistics();
                long depth = stats.getMessages().getCount();
                long enqueued = stats.getEnqueues().getCount();
                long dequeued = stats.getDequeues().getCount();
                long inflight = stats.getInflight().getCount();
                double avgTime = stats.getProcessTime().getAverageTime();
                return new BrokerStats(queueName, depth, enqueued, dequeued, inflight, avgTime);
            }
        } catch (Exception ignored) {
        }
        return new BrokerStats(queueName, 0, 0, 0, 0, 0.0);
    }

    public Map<String, Object> getAllQueueStats() {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String q : new String[]{"ingest.requests", "books.downloaded", "ActiveMQ.DLQ"}) {
            map.put(q, getQueueStats(q).toMap());
        }
        return map;
    }

    @Override
    public void close() throws Exception {
        if (broker != null) {
            broker.stop();
            broker.waitUntilStopped();
            LOG.info("ActiveMQ broker stopped");
        }
    }
}