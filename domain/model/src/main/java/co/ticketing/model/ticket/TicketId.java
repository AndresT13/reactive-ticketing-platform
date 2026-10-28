package co.ticketing.model.ticket;

import co.ticketing.model.common.Validations;

public record TicketId(String value) {

    public TicketId {
        Validations.requireIdentifier(value, "ticketId");
    }
}
