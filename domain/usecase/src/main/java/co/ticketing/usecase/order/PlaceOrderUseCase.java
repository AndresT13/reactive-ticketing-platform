package co.ticketing.usecase.order;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.common.exception.ActiveOrderExistsException;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.IdempotencyKeyReusedException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.idempotency.RequestFingerprint;
import co.ticketing.model.idempotency.gateways.IdempotencyStore;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import co.ticketing.model.order.AdmissionResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.model.order.gateways.OrderAdmissionStore;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.usecase.common.RetryPolicies;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Synchronous admission of a purchase (RF-03). Persists the order as PENDING, publishes the
 * reservation command and returns immediately; the reservation itself happens asynchronously.
 */
public final class PlaceOrderUseCase {

    private final EventRepository events;
    private final InventoryRepository inventories;
    private final IdempotencyStore idempotency;
    private final OrderAdmissionStore admissions;
    private final OrderRepository orders;
    private final OrderCommandPublisher publisher;
    private final IdGenerator ids;
    private final Clock clock;
    private final OrderPolicy policy;

    public PlaceOrderUseCase(EventRepository events, InventoryRepository inventories, IdempotencyStore idempotency,
                             OrderAdmissionStore admissions, OrderRepository orders, OrderCommandPublisher publisher,
                             IdGenerator ids, Clock clock, OrderPolicy policy) {
        this.events = events;
        this.inventories = inventories;
        this.idempotency = idempotency;
        this.admissions = admissions;
        this.orders = orders;
        this.publisher = publisher;
        this.ids = ids;
        this.clock = clock;
        this.policy = policy;
    }

    public Mono<PlaceOrderResult> execute(PlaceOrderCommand command) {
        String scopedKey = IdempotencyRecord.scopedKey(command.customerId(), command.idempotencyKey());
        String fingerprint = RequestFingerprint.of(command.eventId().value(), command.quantity().value());
        return idempotency.find(scopedKey)
                .filter(existing -> !existing.isExpiredAt(clock.instant()))
                .flatMap(existing -> replay(existing, fingerprint))
                .switchIfEmpty(Mono.defer(() -> admitNew(command, scopedKey, fingerprint)));
    }

    private Mono<PlaceOrderResult> admitNew(PlaceOrderCommand command, String scopedKey, String fingerprint) {
        Instant now = clock.instant();
        // Independent reads run in parallel (Mono.zip); dependent steps are chained with flatMap.
        return Mono.zip(requireOnSaleEvent(command.eventId(), now), requireInventory(command.eventId()))
                .flatMap(tuple -> admit(command, tuple.getT2(), scopedKey, fingerprint, now))
                .flatMap(result -> onAdmission(result, fingerprint));
    }

    private Mono<Event> requireOnSaleEvent(EventId eventId, Instant now) {
        return events.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId.value())))
                .filter(event -> event.isOnSaleAt(now))
                .switchIfEmpty(Mono.error(() -> new SalesClosedException(eventId.value())));
    }

    private Mono<Inventory> requireInventory(EventId eventId) {
        return inventories.findByEventId(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId.value())));
    }

    private Mono<AdmissionResult> admit(PlaceOrderCommand command, Inventory inventory, String scopedKey,
                                        String fingerprint, Instant now) {
        // Fast-fail: not authoritative (the worker's conditional write is), it only avoids useless work.
        if (!inventory.canAccommodate(command.quantity())) {
            return Mono.error(new SoldOutException(command.eventId().value(), command.quantity().value()));
        }
        Order order = Order.place(new OrderId(ids.newId()), command.eventId(), command.customerId(),
                command.quantity(), now, policy);
        IdempotencyRecord record = new IdempotencyRecord(scopedKey, fingerprint, order.id(), now.plus(policy.idempotencyTtl()));
        return admissions.admit(order, record).transform(RetryPolicies.onTransientFailure());
    }

    private Mono<PlaceOrderResult> onAdmission(AdmissionResult result, String fingerprint) {
        return switch (result) {
            case AdmissionResult.Admitted(var order) -> dispatch(order);
            case AdmissionResult.IdempotencyConflict(var existing) -> replay(existing, fingerprint);
            case AdmissionResult.ActiveOrderExists(var eventId, _) -> Mono.error(new ActiveOrderExistsException(eventId.value()));
        };
    }

    private Mono<PlaceOrderResult> replay(IdempotencyRecord existing, String fingerprint) {
        if (!existing.matches(fingerprint)) {
            return Mono.error(new IdempotencyKeyReusedException());
        }
        return orders.findById(existing.orderId())
                .map(PlaceOrderResult::replayed)
                .switchIfEmpty(Mono.error(() -> new OrderNotFoundException(existing.orderId().value())));
    }

    private Mono<PlaceOrderResult> dispatch(Order order) {
        return publisher.publish(new ReserveTicketsCommand(order.id(), order.eventId(), order.quantity(), clock.instant()))
                .thenReturn(PlaceOrderResult.accepted(order, true))
                // The order is already durable as PENDING: the housekeeping process re-dispatches it.
                .onErrorResume(error -> Mono.just(PlaceOrderResult.accepted(order, false)));
    }
}
