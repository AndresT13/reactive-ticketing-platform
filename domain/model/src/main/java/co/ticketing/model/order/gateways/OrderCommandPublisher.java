package co.ticketing.model.order.gateways;

import co.ticketing.model.order.OrderCommand;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface OrderCommandPublisher {

    /** Publishes the command to its queue (at-least-once). */
    Mono<Void> publish(OrderCommand command);
}
