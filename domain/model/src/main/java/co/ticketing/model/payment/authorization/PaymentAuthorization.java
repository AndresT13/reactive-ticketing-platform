package co.ticketing.model.payment.authorization;

import java.time.Instant;
import java.util.Objects;

public record PaymentAuthorization(
        String orderId,
        Status status,
        String authorizationId,
        String declineReason,
        Instant updatedAt,
        long version
) {
    public enum Status {
        PENDING,
        APPROVED,
        DECLINED,
        NEEDS_RECONCILIATION
    }

    public PaymentAuthorization {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(status);
        Objects.requireNonNull(updatedAt);

        if (orderId.isBlank() || version < 0) {
            throw new IllegalArgumentException(
                    "Invalid payment authorization identity or version");
        }

        if (status == Status.APPROVED &&
                (authorizationId == null || authorizationId.isBlank())) {
            throw new IllegalArgumentException(
                    "Approved payment requires authorizationId");
        }

        if (status == Status.DECLINED &&
                (declineReason == null || declineReason.isBlank())) {
            throw new IllegalArgumentException(
                    "Declined payment requires reason");
        }
    }

    public static PaymentAuthorization pending(
            String orderId, Instant now) {
        return new PaymentAuthorization(
                orderId, Status.PENDING, null, null, now, 0);
    }
}
