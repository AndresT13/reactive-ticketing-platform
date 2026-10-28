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
import co.ticketing.model.payment.gateways.PaymentGateway;
import co.ticketing.usecase.common.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** Asynchronous step (SQS consumer): authorizes the payment and settles the order as SOLD or REJECTED. */
public final class ProcessPaymentUseCase {

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final PaymentGateway payments;
    private final Clock clock;

    public ProcessPaymentUseCase(OrderRepository orders, OrderTransitionStore store, PaymentGateway payments, Clock clock) {
        this.orders = orders;
        this.store = store;
        this.payments = payments;
        this.clock = clock;
    }

    public Mono<PaymentOutcome> execute(ProcessPaymentCommand command) {
        return Mono.defer(() -> orders.findById(command.orderId()))
                .filter(order -> order.status() == OrderStatus.PENDING_CONFIRMATION)
                .flatMap(order -> payments.authorize(new PaymentRequest(order.id(), order.customerId(),
                                order.quantity(), command.paymentToken()))
                        .flatMap(result -> settle(order, result)))
                .switchIfEmpty(Mono.fromSupplier(() -> new PaymentOutcome.AlreadyProcessed(command.orderId())))
                .transform(RetryPolicies.onTransientFailure());
    }

    private Mono<PaymentOutcome> settle(Order order, PaymentResult result) {
        Instant now = clock.instant();
        return switch (result) {
            case PaymentResult.Approved(var authorizationId) -> store.commit(order.markSold(now))
                    .<PaymentOutcome>map(commit -> commit instanceof CommitResult.Committed(var transition)
                            ? new PaymentOutcome.Sold(transition.after())
                            : new PaymentOutcome.RefundRequired(order.id(), authorizationId));
            case PaymentResult.Declined(var reason) -> store.commit(order.reject(RejectionReason.PAYMENT_DECLINED, Order.WORKER_ACTOR, now))
                    .<PaymentOutcome>map(commit -> commit instanceof CommitResult.Committed(var transition)
                            ? new PaymentOutcome.Declined(transition.after(), reason)
                            : new PaymentOutcome.AlreadyProcessed(order.id()));
        };
    }
}
