package co.ticketing.model.payment.compensation;

import reactor.core.publisher.Mono;

public interface PaymentCompensationRepository {

    Mono<Boolean> createIfAbsent(PaymentCompensation compensation);

    Mono<PaymentCompensation> findById(String compensationId);

    Mono<Boolean> updateIfVersionMatches(
            PaymentCompensation expected,
            PaymentCompensation updated
    );
}
