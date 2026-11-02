package co.ticketing.model.payment.refund;

import reactor.core.publisher.Mono;

/**
 * Port for voiding or refunding a previously approved authorization.
 *
 * Implementations must honor the idempotency key: repeated requests
 * with the same key must not create additional refunds.
 *
 * Transient failures must propagate as errors for controlled retry.
 */
public interface PaymentRefundGateway {

    Mono<RefundResult> refund(
            String authorizationId,
            String idempotencyKey
    );
}
