package co.ticketing.usecase.order;

import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.usecase.payment.AuthorizePaymentUseCase;
import co.ticketing.usecase.payment.ReconcilePaymentAuthorizationUseCase;
import co.ticketing.model.payment.authorization.PaymentAuthorization;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** Asynchronous step (SQS consumer): authorizes the payment and settles the order as SOLD or REJECTED. */
public final class ProcessPaymentUseCase {

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final AuthorizePaymentUseCase payments;
    private final Clock clock;
    private final ReconcilePaymentAuthorizationUseCase reconciliation;

    public ProcessPaymentUseCase(
            OrderRepository orders,
            OrderTransitionStore store,
            AuthorizePaymentUseCase payments,
            Clock clock) {
        this(orders, store, payments, clock, null);
    }

    public ProcessPaymentUseCase(
            OrderRepository orders,
            OrderTransitionStore store,
            AuthorizePaymentUseCase payments,
            Clock clock,
            ReconcilePaymentAuthorizationUseCase reconciliation) {
        this.orders = orders;
        this.store = store;
        this.payments = payments;
        this.clock = clock;
        this.reconciliation = reconciliation;
    }

    public Mono<PaymentOutcome> execute(ProcessPaymentCommand command) {
        return Mono.defer(() -> orders.findById(command.orderId()))
                .filter(order -> order.status() == OrderStatus.PENDING_CONFIRMATION)
                .flatMap(order -> payments.execute(new PaymentRequest(order.id(), order.customerId(),
                                order.quantity(), command.paymentToken()))
                        .onErrorResume(
                                AuthorizePaymentUseCase.ReconciliationRequiredException.class,
                                error -> recover(order.id().value(), error))
                        .flatMap(result -> settle(order, result)))
                .switchIfEmpty(Mono.fromSupplier(() -> new PaymentOutcome.AlreadyProcessed(command.orderId())));
    }


    private Mono<PaymentResult> recover(
            String orderId,
            AuthorizePaymentUseCase.ReconciliationRequiredException original) {

        if (reconciliation == null) {
            return Mono.error(original);
        }

        return reconciliation.execute(orderId)
                .flatMap(state -> switch (state.status()) {
                    case APPROVED -> Mono.just(
                            new PaymentResult.Approved(
                                    state.authorizationId()));
                    case DECLINED -> Mono.just(
                            new PaymentResult.Declined(
                                    state.declineReason()));
                    case PENDING, NEEDS_RECONCILIATION ->
                            Mono.error(new IllegalStateException(
                                    "Payment outcome remains uncertain for order "
                                            + orderId));
                });
    }


    private Mono<PaymentOutcome> resolveApprovedConflict(
            Order original, String authorizationId) {

        return orders.findById(original.id())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Order missing after payment conflict: "
                                + original.id().value())))
                .flatMap(current -> switch (current.status()) {
                    case SOLD -> Mono.<PaymentOutcome>just(
                            new PaymentOutcome.AlreadyProcessed(original.id()));

                    case REJECTED, CANCELLED, EXPIRED ->
                            Mono.<PaymentOutcome>just(
                                    new PaymentOutcome.RefundRequired(
                                            original.id(), authorizationId));

                    case PENDING, RESERVED, PENDING_CONFIRMATION, COMPLIMENTARY ->
                            Mono.<PaymentOutcome>error(
                                    new IllegalStateException(
                                            "Cannot determine payment settlement for order "
                                                    + original.id().value()
                                                    + " in state " + current.status()));
                });
    }

    private Mono<PaymentOutcome> settle(Order order, PaymentResult result) {
        Instant now = clock.instant();
        return switch (result) {
            case PaymentResult.Approved(var authorizationId) ->
                    store.commit(order.markSold(now))
                            .flatMap(commit -> {
                                if (commit instanceof CommitResult.Committed(var transition)) {
                                    return Mono.<PaymentOutcome>just(
                                            new PaymentOutcome.Sold(transition.after()));
                                }
                                return resolveApprovedConflict(order, authorizationId);
                            });
            case PaymentResult.Declined(var reason) -> store.commit(order.reject(RejectionReason.PAYMENT_DECLINED, Order.WORKER_ACTOR, now))
                    .<PaymentOutcome>map(commit -> commit instanceof CommitResult.Committed(var transition)
                            ? new PaymentOutcome.Declined(transition.after(), reason)
                            : new PaymentOutcome.AlreadyProcessed(order.id()));
        };
    }
}
