package co.ticketing.usecase.housekeeping;

import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.usecase.common.RetryPolicies;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;

/**
 * Periodic compensation process (RF-06): releases expired reservations (RESERVED -> EXPIRED) and
 * payments that did not settle in time (PENDING_CONFIRMATION -> REJECTED), returning the tickets to
 * the inventory. Safe to run concurrently on several instances thanks to conditional writes.
 */
public final class ReleaseExpiredReservationsUseCase {

    private static final int BUCKET_CONCURRENCY = 4;
    private static final int COMMIT_CONCURRENCY = 8;

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final Clock clock;
    private final OrderPolicy policy;
    private final int maxPerBucket;

    public ReleaseExpiredReservationsUseCase(OrderRepository orders, OrderTransitionStore store, Clock clock,
                                             OrderPolicy policy, int maxPerBucket) {
        this.orders = orders;
        this.store = store;
        this.clock = clock;
        this.policy = policy;
        this.maxPerBucket = maxPerBucket;
    }

    /** Emits the number of released orders. */
    public Mono<Long> execute() {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return Flux.concat(
                            release(OrderStatus.RESERVED, now, order -> order.expire(now)),
                            release(OrderStatus.PENDING_CONFIRMATION, now, order -> order.timeoutPayment(now)))
                    .count();
        });
    }

    private Flux<Order> release(OrderStatus status, Instant now, Function<Order, OrderTransition> step) {
        return Flux.range(0, policy.deadlineBuckets())
                .flatMap(bucket -> orders.findDue(status, bucket, now, maxPerBucket), BUCKET_CONCURRENCY)
                // The deadline index is eventually consistent: re-check before acting.
                .filter(order -> order.status() == status && order.isDueAt(now))
                .flatMap(order -> Mono.defer(() -> store.commit(step.apply(order)))
                        .transform(RetryPolicies.<CommitResult>onTransientFailure())
                        // One failing order must not abort the whole sweep; it is retried on the next tick.
                        .onErrorResume(error -> Mono.empty()), COMMIT_CONCURRENCY)
                .filter(CommitResult.Committed.class::isInstance)
                .map(result -> result.transition().after());
    }
}
