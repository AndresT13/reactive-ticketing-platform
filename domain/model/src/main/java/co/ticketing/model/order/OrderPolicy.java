package co.ticketing.model.order;

import java.time.Duration;

/**
 * Business timing rules of the order process.
 *
 * @param reservationTtl          how long tickets stay RESERVED without confirmation (10 minutes)
 * @param paymentTimeout          how long an order may stay PENDING_CONFIRMATION before being released
 * @param pendingRedispatchAfter  how long a PENDING order waits before its command is published again
 * @param deadlineBuckets         number of buckets that spread the deadline index partition
 * @param idempotencyTtl          how long an Idempotency-Key is remembered
 */
public record OrderPolicy(Duration reservationTtl, Duration paymentTimeout, Duration pendingRedispatchAfter,
                          int deadlineBuckets, Duration idempotencyTtl) {

    public OrderPolicy {
        requirePositive(reservationTtl, "reservationTtl");
        requirePositive(paymentTimeout, "paymentTimeout");
        requirePositive(pendingRedispatchAfter, "pendingRedispatchAfter");
        requirePositive(idempotencyTtl, "idempotencyTtl");
        if (deadlineBuckets < 1 || deadlineBuckets > 64) {
            throw new IllegalArgumentException("deadlineBuckets must be between 1 and 64");
        }
    }

    public static OrderPolicy defaults() {
        return new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(2), 8, Duration.ofHours(24));
    }

    /** Deterministic bucket of an order inside the deadline index. */
    public int bucketOf(OrderId orderId) {
        return Math.floorMod(orderId.value().hashCode(), deadlineBuckets);
    }

    private static void requirePositive(Duration value, String field) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
