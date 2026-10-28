package co.ticketing.model.order;

import co.ticketing.model.common.Validations;

import java.time.Instant;

/** The payment token is an opaque reference issued by the payment provider; card data never reaches this system. */
public record ProcessPaymentCommand(OrderId orderId, String paymentToken, Instant occurredAt) implements OrderCommand {

    public ProcessPaymentCommand {
        Validations.requirePresent(orderId, "orderId");
        paymentToken = Validations.requireText(paymentToken, "paymentToken", 128);
        Validations.requirePresent(occurredAt, "occurredAt");
    }
}
