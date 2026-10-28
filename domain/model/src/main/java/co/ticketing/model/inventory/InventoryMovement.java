package co.ticketing.model.inventory;

import co.ticketing.model.ticket.TicketStatus;

/**
 * Moves {@code quantity} tickets from one status bucket to another. The same rule produces
 * the in-memory transition and the conditional update expression in the persistence adapter.
 */
public record InventoryMovement(TicketStatus from, TicketStatus to, int quantity) {

    public InventoryMovement {
        if (from == null || to == null || !from.canTransitionTo(to)) {
            throw new IllegalArgumentException("Illegal ticket transition " + from + " -> " + to);
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }
}
