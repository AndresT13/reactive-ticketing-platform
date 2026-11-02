package co.ticketing.usecase.payment;

import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookup;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookupGateway;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import co.ticketing.model.payment.authorization.PaymentAuthorizationTransitions;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.Objects;

public final class ReconcilePaymentAuthorizationUseCase {

    private final PaymentAuthorizationRepository repository;
    private final PaymentAuthorizationLookupGateway gateway;
    private final Clock clock;

    public ReconcilePaymentAuthorizationUseCase(
            PaymentAuthorizationRepository repository,
            PaymentAuthorizationLookupGateway gateway,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mono<PaymentAuthorization> execute(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "orderId is required"));
        }

        return Mono.defer(() -> repository.findByOrderId(orderId)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Authorization not found: " + orderId)))
                .flatMap(this::reconcile));
    }

    private Mono<PaymentAuthorization> reconcile(
            PaymentAuthorization current) {

        if (current.status() == PaymentAuthorization.Status.APPROVED
                || current.status() == PaymentAuthorization.Status.DECLINED) {
            return Mono.just(current);
        }

        return gateway.findByOrderId(current.orderId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Provider returned no reconciliation result")))
                .flatMap(result -> resolve(current, result));
    }

    private Mono<PaymentAuthorization> resolve(
            PaymentAuthorization current,
            PaymentAuthorizationLookup result) {

        if (result instanceof PaymentAuthorizationLookup.Unknown) {
            if (current.status() ==
                    PaymentAuthorization.Status.NEEDS_RECONCILIATION) {
                return Mono.just(current);
            }

            return save(PaymentAuthorizationTransitions.reconcile(
                    current, clock.instant()));
        }

        PaymentAuthorization updated = switch (result) {
            case PaymentAuthorizationLookup.Approved(var id) ->
                    PaymentAuthorizationTransitions.approve(
                            current, id, clock.instant());

            case PaymentAuthorizationLookup.Declined(var reason) ->
                    PaymentAuthorizationTransitions.decline(
                            current, reason, clock.instant());

            case PaymentAuthorizationLookup.Unknown ignored ->
                    throw new IllegalStateException("Unexpected result");
        };

        return save(updated);
    }

    private Mono<PaymentAuthorization> save(
            PaymentAuthorization updated) {

        return repository.updateIfVersionMatches(updated)
                .flatMap(saved -> saved
                        ? Mono.just(updated)
                        : Mono.error(new IllegalStateException(
                                "Concurrent reconciliation conflict: "
                                        + updated.orderId())));
    }
}
