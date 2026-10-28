package co.ticketing.usecase.complimentary;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Quantity;

public record IssueComplimentaryCommand(EventId eventId, CustomerId recipient, Quantity quantity, CustomerId issuedBy) {
}
