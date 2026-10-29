package co.ticketing.dynamodb.mapper;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.ticket.TicketId;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static co.ticketing.dynamodb.mapper.Attributes.instant;
import static co.ticketing.dynamodb.mapper.Attributes.integer;
import static co.ticketing.dynamodb.mapper.Attributes.n;
import static co.ticketing.dynamodb.mapper.Attributes.number;
import static co.ticketing.dynamodb.mapper.Attributes.s;
import static co.ticketing.dynamodb.mapper.Attributes.string;
import static co.ticketing.dynamodb.mapper.Attributes.stringList;

/**
 * Maps the Order aggregate. Non-final orders carry {@code deadlineKey}/{@code deadlineAt}, which feed the
 * sparse {@code deadline-index}; final orders omit them and therefore disappear from the index.
 */
public final class OrderItemMapper {

    public static final String KEY = "orderId";
    public static final String DEADLINE_KEY = "deadlineKey";
    public static final String DEADLINE_AT = "deadlineAt";

    private final OrderPolicy policy;

    public OrderItemMapper(OrderPolicy policy) {
        this.policy = policy;
    }

    public static Map<String, AttributeValue> key(OrderId orderId) {
        return Map.of(KEY, s(orderId.value()));
    }

    public String deadlineKey(OrderStatus status, int bucket) {
        return status.name() + "#" + bucket;
    }

    public Map<String, AttributeValue> toItem(Order order) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(KEY, s(order.id().value()));
        item.put("eventId", s(order.eventId().value()));
        item.put("customerId", s(order.customerId().value()));
        item.put("quantity", n(order.quantity().value()));
        item.put("status", s(order.status().name()));
        item.put("version", n(order.version()));
        item.put("createdAt", instant(order.createdAt()));
        item.put("updatedAt", instant(order.updatedAt()));
        if (!order.ticketIds().isEmpty()) {
            item.put("ticketIds", stringList(order.ticketIds().stream().map(TicketId::value).toList()));
        }
        if (order.expiresAt() != null) {
            item.put("expiresAt", instant(order.expiresAt()));
        }
        if (order.failureReason() != null) {
            item.put("failureReason", s(order.failureReason().name()));
        }
        if (order.deadlineAt() != null && !order.status().isFinal()) {
            item.put(DEADLINE_KEY, s(deadlineKey(order.status(), policy.bucketOf(order.id()))));
            item.put(DEADLINE_AT, instant(order.deadlineAt()));
        }
        return item;
    }

    public static Order toDomain(Map<String, AttributeValue> item) {
        List<TicketId> ticketIds = stringList(item, "ticketIds").stream().map(TicketId::new).toList();
        String failureReason = string(item, "failureReason");
        return new Order(
                new OrderId(string(item, KEY)),
                new EventId(string(item, "eventId")),
                new CustomerId(string(item, "customerId")),
                new Quantity(integer(item, "quantity")),
                OrderStatus.valueOf(string(item, "status")),
                ticketIds,
                instant(item, "expiresAt"),
                instant(item, DEADLINE_AT),
                failureReason == null ? null : RejectionReason.valueOf(failureReason),
                number(item, "version"),
                instant(item, "createdAt"),
                instant(item, "updatedAt"));
    }
}
