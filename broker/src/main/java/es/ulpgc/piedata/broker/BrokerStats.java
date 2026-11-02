package es.ulpgc.piedata.broker;

import java.util.Map;

public record BrokerStats(
        String queueName,
        long queueDepth,
        long totalEnqueued,
        long totalDequeued,
        long inflight,
        double averageTimeInQueueMs
) {
    public Map<String, Object> toMap() {
        return Map.of(
                "depth", queueDepth,
                "enqueued", totalEnqueued,
                "dequeued", totalDequeued,
                "inflight", inflight,
                "average_time_ms", averageTimeInQueueMs
        );
    }
}
