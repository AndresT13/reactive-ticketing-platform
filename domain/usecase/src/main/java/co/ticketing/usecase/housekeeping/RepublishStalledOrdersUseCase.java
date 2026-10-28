package co.ticketing.usecase.housekeeping;

import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Lightweight transactional outbox: a PENDING order whose command was never processed (for example
 * because publishing failed after the order was stored) is published again and its deadline postponed,
 * so the queue is not flooded with duplicates while the workers drain a backlog.
 */
public final class RepublishStalledOrdersUseCase {

    private static final int BUCKET_CONCURRENCY = 4;
    private static final int PUBLISH_CONCURRENCY = 8;

    private final OrderRepository orders;
    private final OrderCommandPublisher publisher;
    private final Clock clock;
    private final OrderPolicy policy;
    private final int maxPerBucket;

    public RepublishStalledOrdersUseCase(OrderRepository orders, OrderCommandPublisher publisher, Clock clock,
                                         OrderPolicy policy, int maxPerBucket) {
        this.orders = orders;
        this.publisher = publisher;
        this.clock = clock;
        this.policy = policy;
        this.maxPerBucket = maxPerBucket;
    }

    /** Emits the number of re-published orders. */
    public Mono<Long> execute() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return Flux.range(0, policy.deadlineBuckets())
                    .flatMap(bucket -> orders.findDue(OrderStatus.PENDING, bucket, now, maxPerBucket), BUCKET_CONCURRENCY)
                    .filter(order -> order.status() == OrderStatus.PENDING && order.isDueAt(now))
                    .flatMap(order -> redispatch(order, now), PUBLISH_CONCURRENCY)
                    .count();
        });
    }

    private Mono<Order> redispatch(Order order, Instant now) {
        return publisher.publish(new ReserveTicketsCommand(order.id(), order.eventId(), order.quantity(), now))
                .then(Mono.defer(() -> orders.postponeDeadline(order.id(), now.plus(policy.pendingRedispatchAfter()))))
                .thenReturn(order)
                .onErrorResume(error -> Mono.empty());
    }
}
