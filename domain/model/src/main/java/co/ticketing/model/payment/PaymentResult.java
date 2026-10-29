package co.ticketing.model.payment;

public sealed interface PaymentResult {

    record Approved(String authorizationId) implements PaymentResult {
    }

    record Declined(String reason) implements PaymentResult {
    }
}
