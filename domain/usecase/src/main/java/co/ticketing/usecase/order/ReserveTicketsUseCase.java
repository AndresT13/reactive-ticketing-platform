package co.ticketing.usecase.order;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.usecase.common.RetryPolicies;
import co.ticketing.usecase.common.TicketIds;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Asynchronous step (SQS consumer): validates the real availability and reserves the tickets.
 * Idempotent: a duplicated message finds the order outside PENDING and becomes a no-op.
 */
public final class ReserveTicketsUseCase {

    private final OrderRepository orders;
    private final OrderTransitionStore store;
    private final IdGenerator ids;
    private final Clock clock;
    private final OrderPolicy policy;

    public ReserveTicketsUseCase(OrderRepository orders, OrderTransitionStore store, IdGenerator ids,
                                 Clock clock, OrderPolicy policy) {
        this.orders = orders;
        this.store = store;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<ReservationOutcome> execute(OrderId orderId) {
        // A retry re-subscribes from the read, so it always works with the latest order version.
        return Mono.defer(() -> orders.findById(orderId))
                .filter(order -> order.status() == OrderStatus.PENDING)
                .flatMap(this::reserve)
                .switchIfEmpty(Mono.fromSupplier(() -> new ReservationOutcome.AlreadyProcessed(orderId)))
                .transform(RetryPolicies.onTransientFailure());
    }

    private Mono<ReservationOutcome> reserve(Order order) {
        Instant now = clock.instant();
        List<TicketId> ticketIds = TicketIds.generate(ids, order.quantity());
        return store.commit(order.reserve(ticketIds, now, policy))
                .flatMap(result -> switch (result) {
                    case CommitResult.Committed(var transition) -> outcome(new ReservationOutcome.Reserved(transition.after()));
                    case CommitResult.InsufficientInventory _ -> rejectAsSoldOut(order, now);
                    case CommitResult.StaleOrder _ -> outcome(new ReservationOutcome.AlreadyProcessed(order.id()));
                });
    }

    private Mono<ReservationOutcome> rejectAsSoldOut(Order order, Instant now) {
        return store.commit(order.reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, now))
                .map(result -> result instanceof CommitResult.Committed(var transition)
                        ? new ReservationOutcome.Rejected(transition.after(), RejectionReason.SOLD_OUT)
                        : new ReservationOutcome.AlreadyProcessed(order.id()));
    }

    private static Mono<ReservationOutcome> outcome(ReservationOutcome outcome) {
        return Mono.just(outcome);
    }
}
