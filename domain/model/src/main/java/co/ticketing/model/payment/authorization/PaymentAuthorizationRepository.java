package co.ticketing.model.payment.authorization;

import reactor.core.publisher.Mono;

public interface PaymentAuthorizationRepository {

    Mono<PaymentAuthorization> findByOrderId(String orderId);

    Mono<Boolean> createIfAbsent(
            PaymentAuthorization authorization);

    Mono<Boolean> updateIfVersionMatches(
            PaymentAuthorization authorization);
}
