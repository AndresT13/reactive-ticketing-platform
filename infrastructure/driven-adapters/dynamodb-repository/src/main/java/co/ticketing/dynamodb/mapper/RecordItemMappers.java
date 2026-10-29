package co.ticketing.dynamodb.mapper;

import co.ticketing.model.audit.AuditEntry;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.ticket.Ticket;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static co.ticketing.dynamodb.mapper.Attributes.instant;
import static co.ticketing.dynamodb.mapper.Attributes.n;
import static co.ticketing.dynamodb.mapper.Attributes.s;
import static co.ticketing.dynamodb.mapper.Attributes.string;
import static co.ticketing.dynamodb.mapper.Attributes.stringList;

/** Mappers for the supporting tables: tickets, audit, idempotency keys and active-order guards. */
public final class RecordItemMappers {

    public static final String TICKET_KEY = "ticketId";
    public static final String AUDIT_KEY = "aggregateId";
    public static final String IDEMPOTENCY_KEY = "scopedKey";
    public static final String GUARD_KEY = "guardKey";

    private RecordItemMappers() {
    }

    public static Map<String, AttributeValue> ticket(Ticket ticket, Instant now) {
        return Map.of(
                TICKET_KEY, s(ticket.id().value()),
                "eventId", s(ticket.eventId().value()),
                "orderId", s(ticket.orderId().value()),
                "status", s(ticket.status().name()),
                "updatedAt", instant(now));
    }

    public static Map<String, AttributeValue> audit(AuditEntry entry) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(AUDIT_KEY, s(entry.aggregateId()));
        item.put("sequence", s("%019d".formatted(entry.sequence())));
        item.put("toStatus", s(entry.toStatus()));
        item.put("quantity", n(entry.quantity()));
        item.put("actor", s(entry.actor()));
        item.put("reason", s(entry.reason()));
        item.put("occurredAt", instant(entry.occurredAt()));
        if (entry.fromStatus() != null) {
            item.put("fromStatus", s(entry.fromStatus()));
        }
        if (!entry.ticketIds().isEmpty()) {
            item.put("ticketIds", stringList(entry.ticketIds()));
        }
        return item;
    }

    public static Map<String, AttributeValue> idempotencyKey(String scopedKey) {
        return Map.of(IDEMPOTENCY_KEY, s(scopedKey));
    }

    public static Map<String, AttributeValue> idempotency(IdempotencyRecord record) {
        return Map.of(
                IDEMPOTENCY_KEY, s(record.scopedKey()),
                "requestHash", s(record.requestHash()),
                "orderId", s(record.orderId().value()),
                "expiresAt", instant(record.expiresAt()),
                // DynamoDB TTL attribute: epoch seconds.
                "ttl", n(record.expiresAt().getEpochSecond()));
    }

    public static IdempotencyRecord toIdempotency(Map<String, AttributeValue> item) {
        return new IdempotencyRecord(string(item, IDEMPOTENCY_KEY), string(item, "requestHash"),
                new OrderId(string(item, "orderId")), Attributes.instant(item, "expiresAt"));
    }

    public static String guardKey(Order order) {
        return order.eventId().value() + "#" + order.customerId().value();
    }

    public static Map<String, AttributeValue> guard(Order order) {
        return Map.of(
                GUARD_KEY, s(guardKey(order)),
                "orderId", s(order.id().value()),
                "createdAt", instant(order.createdAt()));
    }
}
