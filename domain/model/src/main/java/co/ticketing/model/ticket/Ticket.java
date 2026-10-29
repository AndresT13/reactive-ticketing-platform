package co.ticketing.model.ticket;

import co.ticketing.model.common.Validations;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.OrderId;

/** A sellable unit (general admission). It always has exactly one status. */
public record Ticket(TicketId id, EventId eventId, OrderId orderId, TicketStatus status) {

    public Ticket {
        Validations.requirePresent(id, "ticketId");
        Validations.requirePresent(eventId, "eventId");
        Validations.requirePresent(orderId, "orderId");
        Validations.requirePresent(status, "status");
    }
}
