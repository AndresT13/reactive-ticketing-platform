package co.ticketing.model.order;

import co.ticketing.model.common.Validations;
import co.ticketing.model.event.EventId;

import java.time.Instant;

public record ReserveTicketsCommand(OrderId orderId, EventId eventId, Quantity quantity, Instant occurredAt)
        implements OrderCommand {

    public ReserveTicketsCommand {
        Validations.requirePresent(orderId, "orderId");
        Validations.requirePresent(eventId, "eventId");
        Validations.requirePresent(quantity, "quantity");
        Validations.requirePresent(occurredAt, "occurredAt");
    }
}
