package co.ticketing.model.order.gateways;

import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface OrderRepository {

    /** Strongly consistent read. Completes empty when the order does not exist. */
    Mono<Order> findById(OrderId orderId);

    /** Non-final orders in {@code status} whose deadline is at or before {@code now}, for one index bucket. */
    Flux<Order> findDue(OrderStatus status, int bucket, Instant now, int limit);

    /** Moves the deadline of a still-PENDING order forward. Does nothing if the order already progressed. */
    Mono<Void> postponeDeadline(OrderId orderId, Instant newDeadline);
}
