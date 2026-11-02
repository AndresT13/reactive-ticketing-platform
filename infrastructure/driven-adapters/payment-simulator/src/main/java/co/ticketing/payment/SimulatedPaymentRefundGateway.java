package co.ticketing.payment;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.payment.refund.PaymentRefundGateway;
import co.ticketing.model.payment.refund.RefundResult;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Deterministic refund simulator.
 * Does not move real money.
 */
public final class SimulatedPaymentRefundGateway
        implements PaymentRefundGateway {

    private final Duration latency;

    public SimulatedPaymentRefundGateway(Duration latency) {
        this.latency = Objects.requireNonNull(latency);
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency cannot be negative");
        }
    }

    @Override
    public Mono<RefundResult> refund(
            String authorizationId,
            String idempotencyKey
    ) {
        return Mono.defer(() -> {
            requireNonBlank(authorizationId, "authorizationId");
            requireNonBlank(idempotencyKey, "idempotencyKey");

            return Mono.delay(latency)
                    .then(Mono.defer(() -> decide(authorizationId)));
        });
    }

    private static Mono<RefundResult> decide(String authorizationId) {
        if (authorizationId.startsWith("auth-unavailable")) {
            return Mono.error(new TransientFailureException(
                    "Refund provider unavailable"));
        }

        if (authorizationId.startsWith("auth-review")) {
            return Mono.just(
                    new RefundResult.RequiresManualReview(
                            "Refund requires manual reconciliation"));
        }

        // Stable identifier for repeated refund requests.
        return Mono.just(
                new RefundResult.Refunded("refund-" + authorizationId));
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
