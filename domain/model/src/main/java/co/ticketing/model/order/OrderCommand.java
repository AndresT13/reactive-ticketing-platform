package co.ticketing.model.order;

import java.time.Instant;

/** Commands exchanged asynchronously between the API and the workers (Command pattern). */
public sealed interface OrderCommand permits ReserveTicketsCommand, ProcessPaymentCommand {

    OrderId orderId();

    Instant occurredAt();
}
