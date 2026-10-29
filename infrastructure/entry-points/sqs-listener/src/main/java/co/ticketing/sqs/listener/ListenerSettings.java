package co.ticketing.sqs.listener;

import java.time.Duration;

/**
 * @param maxMessages       messages per receive call (1..10)
 * @param waitTimeSeconds   long-polling wait (0..20)
 * @param concurrency       messages processed in parallel per instance (bulkhead)
 * @param processingTimeout maximum time to process one message; must stay below the visibility timeout
 * @param errorBackoff      pause after a failed receive call
 */
public record ListenerSettings(int maxMessages, int waitTimeSeconds, int concurrency, Duration processingTimeout,
                               Duration errorBackoff) {

    public ListenerSettings {
        if (maxMessages < 1 || maxMessages > 10) {
            throw new IllegalArgumentException("maxMessages must be between 1 and 10");
        }
        if (waitTimeSeconds < 0 || waitTimeSeconds > 20) {
            throw new IllegalArgumentException("waitTimeSeconds must be between 0 and 20");
        }
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
    }
}
