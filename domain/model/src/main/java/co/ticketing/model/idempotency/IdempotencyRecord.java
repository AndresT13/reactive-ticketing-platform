package co.ticketing.model.idempotency;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.order.OrderId;

import java.time.Instant;

/**
 * Remembers which order a request produced.
 *
 * @param scopedKey   the key scoped by customer, so two customers can never collide
 * @param requestHash fingerprint of the request payload, to detect a key reused for another request
 */
public record IdempotencyRecord(String scopedKey, String requestHash, OrderId orderId, Instant expiresAt) {

    public static String scopedKey(CustomerId customerId, IdempotencyKey key) {
        return customerId.value() + "#" + key.value();
    }

    public boolean matches(String fingerprint) {
        return requestHash.equals(fingerprint);
    }

    public boolean isExpiredAt(Instant now) {
        return !expiresAt.isAfter(now);
    }
}
