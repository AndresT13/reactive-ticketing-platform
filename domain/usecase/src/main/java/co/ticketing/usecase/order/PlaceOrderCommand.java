package co.ticketing.usecase.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.idempotency.IdempotencyKey;
import co.ticketing.model.order.Quantity;

public record PlaceOrderCommand(CustomerId customerId, EventId eventId, Quantity quantity, IdempotencyKey idempotencyKey) {
}
