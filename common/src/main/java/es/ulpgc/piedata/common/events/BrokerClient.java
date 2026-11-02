package es.ulpgc.piedata.common.events;

import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQPrefetchPolicy;
import org.apache.activemq.RedeliveryPolicy;

import javax.jms.Connection;
import javax.jms.JMSException;

public final class BrokerClient implements AutoCloseable{
    private final ActiveMQConnectionFactory factory;
    private Connection connection;

    public BrokerClient(String rawBrokerUrl) {
        this(rawBrokerUrl, 1000L, 5);
    }

    public BrokerClient(String rawBrokerUrl, long initialRedeliveryDelay, int maxRedeliveries) {
        String brokerUrl = formatFailoverUrl(rawBrokerUrl);
        this.factory = new ActiveMQConnectionFactory(brokerUrl);

        ActiveMQPrefetchPolicy prefetch = new ActiveMQPrefetchPolicy();
        prefetch.setQueuePrefetch(1);
        prefetch.setTopicPrefetch(1);
        this.factory.setPrefetchPolicy(prefetch);

        RedeliveryPolicy redelivery = new RedeliveryPolicy();
        redelivery.setMaximumRedeliveries(maxRedeliveries);
        redelivery.setInitialRedeliveryDelay(initialRedeliveryDelay);
        redelivery.setBackOffMultiplier(2.0);
        redelivery.setUseExponentialBackOff(true);
        this.factory.setRedeliveryPolicy(redelivery);
    }

    public static String formatFailoverUrl(String url) {
        if (url.startsWith("failover:") || url.startsWith("vm:")) {
            return url;
        }
        return "failover:(" + url + ")?randomize=false&priorityBackup=true";
    }

    public synchronized Connection getConnection() throws JMSException {
        if (connection == null) {
            connection = factory.createConnection();
            connection.start();
        }
        return connection;
    }

    public ActiveMQConnectionFactory getFactory() {
        return factory;
    }
    @Override
    public synchronized void close() throws JMSException {
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
