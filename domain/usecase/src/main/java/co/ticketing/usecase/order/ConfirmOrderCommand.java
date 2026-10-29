package co.ticketing.usecase.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.order.OrderId;

public record ConfirmOrderCommand(OrderId orderId, CustomerId customerId, String paymentToken) {
}
