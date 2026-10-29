package co.ticketing.model;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

public final class Fixtures {

    public static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");
    public static final OrderPolicy POLICY = OrderPolicy.defaults();
    public static final EventId EVENT_ID = new EventId("evt-1");
    public static final CustomerId CUSTOMER = new CustomerId("customer-1");
    public static final CustomerId OTHER_CUSTOMER = new CustomerId("customer-2");

    private Fixtures() {
    }

    public static Order pendingOrder(int quantity) {
        return Order.place(new OrderId("ord-1"), EVENT_ID, CUSTOMER, new Quantity(quantity), NOW, POLICY);
    }

    public static List<TicketId> ticketIds(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new TicketId("tkt-" + i)).toList();
    }

    public static Order reservedOrder(int quantity) {
        return pendingOrder(quantity).reserve(ticketIds(quantity), NOW, POLICY).after();
    }

    public static Order pendingConfirmationOrder(int quantity) {
        return reservedOrder(quantity).requestConfirmation(CUSTOMER, NOW.plusSeconds(60), POLICY).after();
    }
}
