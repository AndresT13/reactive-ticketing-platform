package co.ticketing.usecase;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.Venue;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

public final class UseCaseFixtures {

    public static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    public static final OrderPolicy POLICY = OrderPolicy.defaults();
    public static final EventId EVENT_ID = new EventId("evt-1");
    public static final CustomerId CUSTOMER = new CustomerId("customer-1");
    public static final CustomerId OTHER = new CustomerId("customer-2");
    public static final OrderId ORDER_ID = new OrderId("ord-1");

    private UseCaseFixtures() {
    }

    public static IdGenerator sequentialIds() {
        AtomicLong counter = new AtomicLong();
        return () -> "id-" + counter.incrementAndGet();
    }

    public static Event event() {
        return new Event(EVENT_ID, "Rock Fest", new Venue("Arena", "Bogotá"), NOW.plusSeconds(86_400), 100, NOW.minusSeconds(60));
    }

    public static Event pastEvent() {
        return new Event(EVENT_ID, "Old Fest", new Venue("Arena", "Bogotá"), NOW.minusSeconds(60), 100, NOW.minusSeconds(86_400));
    }

    public static Order pending(int quantity) {
        return Order.place(ORDER_ID, EVENT_ID, CUSTOMER, new Quantity(quantity), NOW, POLICY);
    }

    public static List<TicketId> tickets(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new TicketId("tkt-" + i)).toList();
    }

    public static Order reserved(int quantity) {
        return pending(quantity).reserve(tickets(quantity), NOW, POLICY).after();
    }

    public static Order pendingConfirmation(int quantity) {
        return reserved(quantity).requestConfirmation(CUSTOMER, NOW, POLICY).after();
    }
}
