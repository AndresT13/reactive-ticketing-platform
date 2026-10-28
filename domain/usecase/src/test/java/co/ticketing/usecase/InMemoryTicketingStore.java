package co.ticketing.usecase;

import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test double that reproduces the semantics of the DynamoDB transaction with a single
 * compare-and-set over an immutable snapshot: the inventory condition ({@code bucket >= q}) and the
 * order condition (expected status and version) are evaluated and applied atomically.
 */
public final class InMemoryTicketingStore implements OrderTransitionStore, OrderRepository {

    private record Snapshot(Inventory inventory, Map<OrderId, Order> orders) {
    }

    private final AtomicReference<Snapshot> state;

    public InMemoryTicketingStore(EventId eventId, int capacity) {
        this.state = new AtomicReference<>(new Snapshot(Inventory.initial(eventId, capacity), Map.of()));
    }

    public void save(Order order) {
        state.updateAndGet(snapshot -> {
            Map<OrderId, Order> orders = new HashMap<>(snapshot.orders());
            orders.put(order.id(), order);
            return new Snapshot(snapshot.inventory(), Map.copyOf(orders));
        });
    }

    public Inventory inventory() {
        return state.get().inventory();
    }

    @Override
    public Mono<CommitResult> commit(OrderTransition transition) {
        return Mono.fromSupplier(() -> {
            while (true) {
                Snapshot current = state.get();
                Order stored = current.orders().get(transition.after().id());
                Order expected = transition.before();
                boolean orderConditionHolds = expected == null
                        ? stored == null
                        : stored != null && stored.status() == expected.status() && stored.version() == expected.version();
                if (transition.movement() != null
                        && current.inventory().count(transition.movement().from()) < transition.movement().quantity()) {
                    return new CommitResult.InsufficientInventory(transition);
                }
                if (!orderConditionHolds) {
                    return new CommitResult.StaleOrder(transition);
                }
                Inventory inventory = transition.movement() == null
                        ? current.inventory()
                        : current.inventory().apply(transition.movement());
                Map<OrderId, Order> orders = new HashMap<>(current.orders());
                orders.put(transition.after().id(), transition.after());
                if (state.compareAndSet(current, new Snapshot(inventory, Map.copyOf(orders)))) {
                    return new CommitResult.Committed(transition);
                }
            }
        });
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        return Mono.fromSupplier(() -> state.get().orders().get(orderId));
    }

    @Override
    public Flux<Order> findDue(OrderStatus status, int bucket, Instant now, int limit) {
        return Flux.fromIterable(state.get().orders().values())
                .filter(order -> order.status() == status && order.isDueAt(now))
                .take(limit);
    }

    @Override
    public Mono<Void> postponeDeadline(OrderId orderId, Instant newDeadline) {
        return Mono.empty();
    }
}
