package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.Attributes;
import co.ticketing.dynamodb.mapper.EventItemMapper;
import co.ticketing.dynamodb.mapper.InventoryItemMapper;
import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.audit.AuditEntry;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.Venue;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.ticket.Ticket;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

import static co.ticketing.dynamodb.DynamoDbFixtures.EVENT_ID;
import static co.ticketing.dynamodb.DynamoDbFixtures.NOW;
import static co.ticketing.dynamodb.DynamoDbFixtures.POLICY;
import static co.ticketing.dynamodb.DynamoDbFixtures.pending;
import static co.ticketing.dynamodb.DynamoDbFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;

class ItemMappersTest {

    private final OrderItemMapper orderMapper = new OrderItemMapper(POLICY);

    @Test
    void eventRoundTrip() {
        Event event = new Event(EVENT_ID, "Rock", new Venue("Arena", "Bogotá"), NOW.plusSeconds(60), 10, NOW);

        assertThat(EventItemMapper.toDomain(EventItemMapper.toItem(event))).isEqualTo(event);
        assertThat(EventItemMapper.key(EVENT_ID)).containsKey("eventId");
    }

    @Test
    void inventoryRoundTripAndBuckets() {
        Inventory inventory = new Inventory(EVENT_ID, 10, 4, 3, 1, 1, 1, 7);

        assertThat(InventoryItemMapper.toDomain(InventoryItemMapper.toItem(inventory, NOW))).isEqualTo(inventory);
        assertThat(TicketStatus.values()).extracting(InventoryItemMapper::bucketAttribute)
                .containsExactly("available", "reserved", "pendingConfirmation", "sold", "complimentary");
    }

    @Test
    void nonFinalOrdersAreIndexedByDeadline() {
        Order order = reserved(2);
        Map<String, AttributeValue> item = orderMapper.toItem(order);

        assertThat(item.get(OrderItemMapper.DEADLINE_KEY).s())
                .isEqualTo("RESERVED#" + POLICY.bucketOf(order.id()));
        assertThat(item.get(OrderItemMapper.DEADLINE_AT).n()).isEqualTo(Long.toString(order.expiresAt().toEpochMilli()));
        assertThat(OrderItemMapper.toDomain(item)).isEqualTo(order);
    }

    @Test
    void finalOrdersLeaveTheSparseIndex() {
        Order rejected = pending(1).reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, NOW).after();
        Map<String, AttributeValue> item = orderMapper.toItem(rejected);

        assertThat(item).doesNotContainKeys(OrderItemMapper.DEADLINE_KEY, OrderItemMapper.DEADLINE_AT, "ticketIds", "expiresAt");
        assertThat(OrderItemMapper.toDomain(item)).isEqualTo(rejected);
        assertThat(OrderItemMapper.key(new OrderId("o"))).containsKey("orderId");
    }

    @Test
    void supportingRecords() {
        Order order = reserved(1);
        Ticket ticket = new Ticket(new TicketId("t-1"), EVENT_ID, order.id(), TicketStatus.RESERVED);
        IdempotencyRecord record = new IdempotencyRecord("c#k", "hash", order.id(), NOW.plusSeconds(60));
        AuditEntry withHistory = new AuditEntry("ord-1", 2, "PENDING", "RESERVED", 1, List.of("t-1"), "a", "r", NOW);
        AuditEntry creation = new AuditEntry("ord-1", 0, null, "PENDING", 0, List.of(), "a", "r", NOW);

        assertThat(RecordItemMappers.ticket(ticket, NOW).get("status").s()).isEqualTo("RESERVED");
        assertThat(RecordItemMappers.toIdempotency(RecordItemMappers.idempotency(record))).isEqualTo(record);
        assertThat(RecordItemMappers.idempotency(record).get("ttl").n()).isEqualTo(Long.toString(NOW.plusSeconds(60).getEpochSecond()));
        assertThat(RecordItemMappers.audit(withHistory)).containsKeys("fromStatus", "ticketIds");
        assertThat(RecordItemMappers.audit(withHistory).get("sequence").s()).isEqualTo("0000000000000000002");
        assertThat(RecordItemMappers.audit(creation)).doesNotContainKeys("fromStatus", "ticketIds");
        assertThat(RecordItemMappers.guard(order).get("guardKey").s()).isEqualTo("evt-1#customer-1");
        assertThat(RecordItemMappers.idempotencyKey("c#k")).containsKey("scopedKey");
    }

    @Test
    void attributeHelpersHandleMissingValues() {
        Map<String, AttributeValue> item = Map.of("n", AttributeValue.fromS("not-a-number"));

        assertThat(Attributes.string(item, "missing")).isNull();
        assertThat(Attributes.instant(item, "missing")).isNull();
        assertThat(Attributes.instant(item, "n")).isNull();
        assertThat(Attributes.stringList(item, "missing")).isEmpty();
        assertThat(Attributes.stringList(Map.of("l", Attributes.stringList(List.of("a"))), "l")).containsExactly("a");
        assertThat(new EventId("e")).isNotNull();
    }
}
