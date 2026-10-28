package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.usecase.common.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;

/**
 * Mixed step: the transition RESERVED -> PENDING_CONFIRMATION is synchronous (the 10-minute window is
 * checked at the exact moment of the request) and the payment is processed asynchronously.
 * Idempotent: repeating the call returns the current state and re-publishes the payment command.
 */
public final class ConfirmOrderUseCase {

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final OrderCommandPublisher publisher;
    private final Clock clock;
    private final OrderPolicy policy;

    public ConfirmOrderUseCase(OrderRepository orders, OrderTransitionStore store, OrderCommandPublisher publisher,
                               Clock clock, OrderPolicy policy) {
        this.orders = orders;
        this.store = store;
        this.publisher = publisher;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<Order> execute(ConfirmOrderCommand command) {
        return Mono.defer(() -> orders.findById(command.orderId()))
                .filter(order -> order.isOwnedBy(command.customerId()))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(command.orderId().value())))
                .flatMap(order -> switch (order.status()) {
                    case PENDING_CONFIRMATION -> dispatchPayment(order, command.paymentToken());
                    case SOLD -> Mono.just(order);
                    default -> confirm(order, command);
                })
                .transform(RetryPolicies.onTransientFailure());
    }

    private Mono<Order> confirm(Order order, ConfirmOrderCommand command) {
        OrderTransition transition = order.requestConfirmation(command.customerId(), clock.instant(), policy);
        return store.commit(transition).flatMap(result -> {
            if (result instanceof CommitResult.Committed(var committed)) {
                return dispatchPayment(committed.after(), command.paymentToken());
            }
            // The sweeper or a cancellation won the race.
            return Mono.error(new InvalidStateTransitionException(order.id().value(), order.status().name(),
                    OrderStatus.PENDING_CONFIRMATION.name()));
        });
    }

    private Mono<Order> dispatchPayment(Order order, String paymentToken) {
        return publisher.publish(new ProcessPaymentCommand(order.id(), paymentToken, clock.instant()))
                .thenReturn(order);
    }
}
