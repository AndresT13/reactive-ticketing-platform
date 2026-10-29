package co.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Externalized configuration (12-factor): every value can be overridden with an environment variable. */
@ConfigurationProperties(prefix = "ticketing")
public record TicketingProperties(Roles roles, Aws aws, Tables tables, Queues queues, Orders orders,
                                  Listener listener, Scheduler scheduler, Payment payment) {

    /** One image, two runtime roles that scale independently. */
    public record Roles(boolean api, boolean worker) {
    }

    /** Endpoints are only set locally (DynamoDB Local, LocalStack); in AWS the SDK resolves them by region. */
    public record Aws(String region, String dynamodbEndpoint, String sqsEndpoint, int maxConcurrency,
                      Duration apiCallTimeout, Duration apiCallAttemptTimeout) {
    }

    public record Tables(String events, String inventory, String orders, String tickets, String audit,
                         String idempotency, String guards) {
    }

    public record Queues(String reservationRequestsUrl, String paymentRequestsUrl, Duration publishTimeout) {
    }

    public record Orders(Duration reservationTtl, Duration paymentTimeout, Duration pendingRedispatchAfter,
                         int deadlineBuckets, Duration idempotencyTtl, int sweepBatchSize) {
    }

    public record Listener(int maxMessages, int waitTimeSeconds, int concurrency, Duration processingTimeout,
                           Duration errorBackoff) {
    }

    public record Scheduler(Duration initialDelay, Duration interval) {
    }

    public record Payment(Duration simulatedLatency) {
    }
}
