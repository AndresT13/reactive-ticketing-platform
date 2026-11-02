package co.ticketing.model.payment.authorization;

import java.time.Instant;
import java.util.Objects;

public final class PaymentAuthorizationTransitions {

    private PaymentAuthorizationTransitions() {
    }

    public static PaymentAuthorization approve(
            PaymentAuthorization current,
            String authorizationId,
            Instant now) {

        requireActive(current);
        requireText(authorizationId, "authorizationId");

        return new PaymentAuthorization(
                current.orderId(),
                PaymentAuthorization.Status.APPROVED,
                authorizationId,
                null,
                Objects.requireNonNull(now),
                current.version() + 1
        );
    }

    public static PaymentAuthorization decline(
            PaymentAuthorization current,
            String reason,
            Instant now) {

        requireActive(current);
        requireText(reason, "reason");

        return new PaymentAuthorization(
                current.orderId(),
                PaymentAuthorization.Status.DECLINED,
                null,
                reason,
                Objects.requireNonNull(now),
                current.version() + 1
        );
    }

    public static PaymentAuthorization reconcile(
            PaymentAuthorization current,
            Instant now) {

        Objects.requireNonNull(current);

        if (current.status() != PaymentAuthorization.Status.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING authorizations can enter reconciliation");
        }

        return new PaymentAuthorization(
                current.orderId(),
                PaymentAuthorization.Status.NEEDS_RECONCILIATION,
                null,
                null,
                Objects.requireNonNull(now),
                current.version() + 1
        );
    }

    private static void requireActive(
            PaymentAuthorization current) {

        Objects.requireNonNull(current);

        if (current.status() != PaymentAuthorization.Status.PENDING
                && current.status() !=
                PaymentAuthorization.Status.NEEDS_RECONCILIATION) {
            throw new IllegalStateException(
                    "Authorization is already terminal: "
                            + current.status());
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
