package es.ulpgc.piedata.common.events;

import es.ulpgc.piedata.common.json.Json;

import javax.jms.Connection;
import javax.jms.DeliveryMode;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;

public final class EventPublisher {
    private final BrokerClient brokerClient;

    public EventPublisher(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    public void publish(String destinationName, boolean isTopic, Object event, long bookId) throws JMSException {
        Connection conn = brokerClient.getConnection();
        try (Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
            Destination destination = isTopic ? session.createTopic(destinationName) : session.createQueue(destinationName);
            publishInSession(session, destination, event, bookId);
        }
    }

    public static void publishInSession(Session session, Destination destination, Object event, long bookId)
            throws JMSException {
        try (MessageProducer producer = session.createProducer(destination)) {
            producer.setDeliveryMode(DeliveryMode.PERSISTENT);
            String json = Json.write(event);
            TextMessage message = session.createTextMessage(json);
            message.setLongProperty("book_id", bookId);
            producer.send(message);
        }
    }

}
