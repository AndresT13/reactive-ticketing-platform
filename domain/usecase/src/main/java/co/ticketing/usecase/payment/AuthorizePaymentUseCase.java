package co.ticketing.usecase.payment;

import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import co.ticketing.model.payment.authorization.PaymentAuthorizationTransitions;
import co.ticketing.model.payment.gateways.PaymentGateway;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

public final class AuthorizePaymentUseCase {

    private final PaymentAuthorizationRepository repository;
    private final PaymentGateway gateway;
    private final Clock clock;

    public AuthorizePaymentUseCase(
            PaymentAuthorizationRepository repository,
            PaymentGateway gateway,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mono<PaymentResult> execute(PaymentRequest request) {
        Objects.requireNonNull(request);

        return Mono.defer(() -> {
            String orderId = request.orderId().value();

            return repository.findByOrderId(orderId)
                    .flatMap(this::existingResult)
                    .switchIfEmpty(Mono.defer(() ->
                            beginAuthorization(request, orderId)));
        });
    }

    private Mono<PaymentResult> beginAuthorization(
            PaymentRequest request, String orderId) {

        var pending = PaymentAuthorization.pending(
                orderId, clock.instant());

        return repository.createIfAbsent(pending)
                .flatMap(created -> {
                    if (!created) {
                        return repository.findByOrderId(orderId)
                                .flatMap(this::existingResult)
                                .switchIfEmpty(Mono.error(
                                        new IllegalStateException(
                                                "Authorization state unavailable")));
                    }

                    return gateway.authorize(request)
                            .flatMap(result -> persistResult(
                                    pending, result));
                });
    }

    private Mono<PaymentResult> existingResult(
            PaymentAuthorization authorization) {

        return switch (authorization.status()) {
            case APPROVED -> Mono.just(
                    new PaymentResult.Approved(
                            authorization.authorizationId()));

            case DECLINED -> Mono.just(
                    new PaymentResult.Declined(
                            authorization.declineReason()));

            case PENDING, NEEDS_RECONCILIATION ->
                    Mono.error(new ReconciliationRequiredException(
                            authorization.orderId()));
        };
    }

    private Mono<PaymentResult> persistResult(
            PaymentAuthorization pending,
            PaymentResult result) {

        var updated = switch (result) {
            case PaymentResult.Approved(var authorizationId) ->
                    PaymentAuthorizationTransitions.approve(
                            pending, authorizationId, clock.instant());

            case PaymentResult.Declined(var reason) ->
                    PaymentAuthorizationTransitions.decline(
                            pending, reason, clock.instant());
        };

        return repository.updateIfVersionMatches(updated)
                .flatMap(saved -> {
                    if (saved) {
                        return Mono.just(result);
                    }

                    return Mono.error(new IllegalStateException(
                            "Authorization persistence conflict for order "
                                    + pending.orderId()));
                });
    }

    public static final class ReconciliationRequiredException
            extends IllegalStateException {

        public ReconciliationRequiredException(String orderId) {
            super("Payment requires reconciliation for order " + orderId);
        }
    }

}
