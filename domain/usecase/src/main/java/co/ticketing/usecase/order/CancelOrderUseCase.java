package co.ticketing.usecase.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.usecase.common.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;

/** Synchronous step: the owner releases a reservation before it expires. Idempotent. */
public final class CancelOrderUseCase {

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final Clock clock;

    public CancelOrderUseCase(OrderRepository orders, OrderTransitionStore store, Clock clock) {
        this.orders = orders;
        this.store = store;
        this.clock = clock;
    }

    public Mono<Order> execute(OrderId orderId, CustomerId requester) {
        return Mono.defer(() -> orders.findById(orderId))
                .filter(order -> order.isOwnedBy(requester))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId.value())))
                .flatMap(order -> order.status() == OrderStatus.CANCELLED ? Mono.just(order) : cancel(order, requester))
                .transform(RetryPolicies.onTransientFailure());
    }

    private Mono<Order> cancel(Order order, CustomerId requester) {
        return store.commit(order.cancel(requester, clock.instant())).flatMap(result -> {
            if (result instanceof CommitResult.Committed(var transition)) {
                return Mono.just(transition.after());
            }
            return Mono.error(new InvalidStateTransitionException(order.id().value(), order.status().name(),
                    OrderStatus.CANCELLED.name()));
        });
    }
}
