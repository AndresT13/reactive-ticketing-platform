package co.ticketing.usecase.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.gateways.OrderRepository;
import reactor.core.publisher.Mono;

/** Current state of an order (RF-04). Customers only see their own orders; administrators see all. */
public final class GetOrderUseCase {

    private final OrderRepository orders;

    public GetOrderUseCase(OrderRepository orders) {
        this.orders = orders;
    }

    public Mono<Order> execute(OrderId orderId, CustomerId requester, boolean requesterIsAdmin) {
        return orders.findById(orderId)
                .filter(order -> requesterIsAdmin || order.isOwnedBy(requester))
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(orderId.value())));
    }
}
