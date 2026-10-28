package co.ticketing.usecase.order;

import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;

public sealed interface PaymentOutcome {

    record Sold(Order order) implements PaymentOutcome {
    }

    record Declined(Order order, String reason) implements PaymentOutcome {
    }

    /**
     * The provider approved the charge but the order had already been released (e.g. payment timeout).
     * The charge must be voided; surfaced as an alert-worthy outcome instead of being silently dropped.
     */
    record RefundRequired(OrderId orderId, String authorizationId) implements PaymentOutcome {
    }

    record AlreadyProcessed(OrderId orderId) implements PaymentOutcome {
    }
}
