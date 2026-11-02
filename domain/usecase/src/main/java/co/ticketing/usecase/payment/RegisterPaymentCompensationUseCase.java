package co.ticketing.usecase.payment;

import co.ticketing.model.payment.compensation.PaymentCompensation;
import co.ticketing.model.payment.compensation.PaymentCompensationRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

public final class RegisterPaymentCompensationUseCase {

    private final PaymentCompensationRepository repository;
    private final Clock clock;

    public RegisterPaymentCompensationUseCase(
            PaymentCompensationRepository repository,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mono<PaymentCompensation> execute(
            String orderId,
            String authorizationId
    ) {
        return Mono.defer(() -> {
            var candidate = PaymentCompensation.pending(
                    orderId, authorizationId, clock.instant());

            return repository.createIfAbsent(candidate)
                    .flatMap(created -> created
                            ? Mono.just(candidate)
                            : repository.findById(candidate.compensationId())
                                .switchIfEmpty(Mono.error(
                                    new IllegalStateException(
                                        "Compensation record missing")))
                                .flatMap(existing -> {
                                    if (!existing.orderId().equals(orderId)
                                            || !existing.authorizationId()
                                                .equals(authorizationId)) {
                                        return Mono.error(
                                            new IllegalStateException(
                                                "Compensation identity conflict"));
                                    }
                                    return Mono.just(existing);
                                }));
        });
    }
}
