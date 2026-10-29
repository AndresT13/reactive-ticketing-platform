package co.ticketing.usecase.complimentary;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.usecase.common.RetryPolicies;
import co.ticketing.usecase.common.TicketIds;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/** Administrative, synchronous operation: AVAILABLE -> COMPLIMENTARY (final, not accountable). */
public final class IssueComplimentaryTicketsUseCase {

    private final EventRepository events;
    private final OrderTransitionStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public IssueComplimentaryTicketsUseCase(EventRepository events, OrderTransitionStore store, IdGenerator ids, Clock clock) {
        this.events = events;
        this.store = store;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Order> execute(IssueComplimentaryCommand command) {
        return Mono.defer(() -> {
            Instant now = clock.instant();
            return events.findById(command.eventId())
                    .switchIfEmpty(Mono.error(() -> new EventNotFoundException(command.eventId().value())))
                    .filter(event -> event.isOnSaleAt(now))
                    .switchIfEmpty(Mono.error(() -> new SalesClosedException(command.eventId().value())))
                    .map(_ -> Order.issueComplimentary(new OrderId(ids.newId()), command.eventId(), command.recipient(),
                            command.quantity(), TicketIds.generate(ids, command.quantity()), command.issuedBy(), now))
                    .flatMap(store::commit)
                    .flatMap(IssueComplimentaryTicketsUseCase::toOrder);
        }).transform(RetryPolicies.onTransientFailure());
    }

    private static Mono<Order> toOrder(CommitResult result) {
        Order order = result.transition().after();
        return switch (result) {
            case CommitResult.Committed _ -> Mono.just(order);
            case CommitResult.InsufficientInventory _ ->
                    Mono.error(new SoldOutException(order.eventId().value(), order.quantity().value()));
            case CommitResult.StaleOrder _ ->
                    Mono.error(new InvalidStateTransitionException(order.id().value(), "NEW", OrderStatus.COMPLIMENTARY.name()));
        };
    }
}
