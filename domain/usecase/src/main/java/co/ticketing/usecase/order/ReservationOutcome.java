package co.ticketing.usecase.order;

import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.RejectionReason;

/** Business outcome of processing a reservation command: a value, never an exception (ADR-014). */
public sealed interface ReservationOutcome {

    record Reserved(Order order) implements ReservationOutcome {
    }

    record Rejected(Order order, RejectionReason reason) implements ReservationOutcome {
    }

    /** Duplicate or late message: the order already left PENDING. Safe to acknowledge. */
    record AlreadyProcessed(OrderId orderId) implements ReservationOutcome {
    }
}
