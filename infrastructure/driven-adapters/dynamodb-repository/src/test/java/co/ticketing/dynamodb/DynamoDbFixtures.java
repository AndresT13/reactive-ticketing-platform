package co.ticketing.dynamodb;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

final class DynamoDbFixtures {

    static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");
    static final OrderPolicy POLICY = OrderPolicy.defaults();
    static final DynamoDbTables TABLES = new DynamoDbTables("events", "inventory", "orders", "tickets", "audit",
            "idempotency", "guards");
    static final EventId EVENT_ID = new EventId("evt-1");
    static final CustomerId CUSTOMER = new CustomerId("customer-1");

    private DynamoDbFixtures() {
    }

    static Order pending(int quantity) {
        return Order.place(new OrderId("ord-1"), EVENT_ID, CUSTOMER, new Quantity(quantity), NOW, POLICY);
    }

    static List<TicketId> tickets(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> new TicketId("tkt-" + i)).toList();
    }

    static Order reserved(int quantity) {
        return pending(quantity).reserve(tickets(quantity), NOW, POLICY).after();
    }

    static TransactionCanceledException cancelled(String... codes) {
        List<CancellationReason> reasons = Arrays.stream(codes)
                .map(code -> CancellationReason.builder().code(code).build())
                .toList();
        return TransactionCanceledException.builder().message("cancelled").cancellationReasons(reasons).build();
    }
}
