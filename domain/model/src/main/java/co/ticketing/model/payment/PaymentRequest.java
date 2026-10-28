package co.ticketing.model.payment;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;

/** {@code orderId} doubles as the provider idempotency key, so a redelivered message never charges twice. */
public record PaymentRequest(OrderId orderId, CustomerId customerId, Quantity quantity, String paymentToken) {
}
