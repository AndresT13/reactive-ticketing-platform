package co.ticketing.web.dto;

import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.ticket.TicketId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code status} is the order process (PENDING ... CANCELLED); {@code ticketStatus} is always one of the
 * five business ticket states (ADR-009). {@code _links} advertises the next valid actions (HATEOAS).
 */
public record OrderResponse(String orderId, String eventId, int quantity, String status, String ticketStatus,
                            List<String> ticketIds, String expiresAt, String failureReason, String createdAt,
                            String updatedAt, Map<String, Link> _links) {

    public static OrderResponse from(Order order) {
        String self = "/api/v1/orders/" + order.id().value();
        Map<String, Link> links = new LinkedHashMap<>();
        links.put("self", new Link(self, "GET"));
        if (order.status() == OrderStatus.RESERVED) {
            links.put("confirm", new Link(self + "/confirm", "POST"));
            links.put("cancel", new Link(self + "/cancel", "POST"));
        }
        links.put("event", new Link("/api/v1/events/" + order.eventId().value(), "GET"));
        return new OrderResponse(order.id().value(), order.eventId().value(), order.quantity().value(),
                order.status().name(), order.ticketStatus().name(),
                order.ticketIds().stream().map(TicketId::value).toList(),
                text(order.expiresAt()), order.failureReason() == null ? null : order.failureReason().name(),
                text(order.createdAt()), text(order.updatedAt()), links);
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
