package co.ticketing.model.payment.authorization;

import reactor.core.publisher.Mono;

public interface PaymentAuthorizationLookupGateway {

    /**
     * Read-only lookup using the stable provider idempotency key.
     *
     * Unknown means that the provider has not confirmed a
     * definitive outcome. It does NOT mean payment was declined
     * or that a fresh authorization is safe.
     */
    Mono<PaymentAuthorizationLookup> findByOrderId(String orderId);
}
