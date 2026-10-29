package co.ticketing.usecase.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.usecase.InMemoryTicketingStore;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.IntStream;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.EVENT_ID;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.POLICY;
import static co.ticketing.usecase.UseCaseFixtures.sequentialIds;
import static org.assertj.core.api.Assertions.assertThat;

/** RF-05 / QAS-01: concurrent requests for the same event never oversell. */
class ConcurrentReservationTest {

    private static final int CAPACITY = 100;
    private static final int BUYERS = 500;

    @Test
    void fiveHundredConcurrentBuyersForOneHundredTicketsReserveExactlyOneHundred() {
        InMemoryTicketingStore store = new InMemoryTicketingStore(EVENT_ID, CAPACITY);
        List<OrderId> orderIds = placeOrders(store);
        ReserveTicketsUseCase useCase = new ReserveTicketsUseCase(store, store, sequentialIds(), CLOCK, POLICY);

        List<ReservationOutcome> outcomes = Flux.fromIterable(orderIds)
                .flatMap(orderId -> useCase.execute(orderId).subscribeOn(Schedulers.parallel()), 64)
                .collectList()
                .block();

        assertThat(outcomes).hasSize(BUYERS);
        assertThat(outcomes).filteredOn(ReservationOutcome.Reserved.class::isInstance).hasSize(CAPACITY);
        assertThat(outcomes).filteredOn(ReservationOutcome.Rejected.class::isInstance).hasSize(BUYERS - CAPACITY);
        assertInvariant(store.inventory());
        assertThat(store.inventory().reserved()).isEqualTo(CAPACITY);
        assertThat(store.inventory().available()).isZero();
    }

    @Test
    void duplicatedMessagesProduceTheSameEffectAsSingleDelivery() {
        InMemoryTicketingStore store = new InMemoryTicketingStore(EVENT_ID, CAPACITY);
        List<OrderId> orderIds = placeOrders(store);
        ReserveTicketsUseCase useCase = new ReserveTicketsUseCase(store, store, sequentialIds(), CLOCK, POLICY);

        List<ReservationOutcome> outcomes = Flux.fromIterable(orderIds)
                .flatMap(orderId -> Flux.just(orderId, orderId))       // at-least-once: every message twice
                .flatMap(orderId -> useCase.execute(orderId).subscribeOn(Schedulers.parallel()), 64)
                .collectList()
                .block();

        assertThat(outcomes).filteredOn(ReservationOutcome.Reserved.class::isInstance).hasSize(CAPACITY);
        assertThat(outcomes).filteredOn(ReservationOutcome.AlreadyProcessed.class::isInstance).hasSize(BUYERS);
        assertInvariant(store.inventory());
        assertThat(store.inventory().reserved()).isEqualTo(CAPACITY);
    }

    private static List<OrderId> placeOrders(InMemoryTicketingStore store) {
        List<Order> orders = IntStream.range(0, BUYERS)
                .mapToObj(i -> Order.place(new OrderId("ord-" + i), EVENT_ID, new CustomerId("c-" + i), new Quantity(1), NOW, POLICY))
                .toList();
        orders.forEach(store::save);
        return orders.stream().map(Order::id).toList();
    }

    private static void assertInvariant(Inventory inventory) {
        assertThat(inventory.available() + inventory.reserved() + inventory.pendingConfirmation()
                + inventory.sold() + inventory.complimentary()).isEqualTo(inventory.totalCapacity());
        assertThat(inventory.available()).isNotNegative();
    }
}
