package co.ticketing.model.order;

import co.ticketing.model.ticket.TicketStatus;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of the purchase process. {@link #allowedTargets()} is the single, explicit definition
 * of the order workflow (Process Manager, ADR-015): every step of the saga is declared here.
 */
public enum OrderStatus {
    PENDING(TicketStatus.AVAILABLE),
    RESERVED(TicketStatus.RESERVED),
    PENDING_CONFIRMATION(TicketStatus.PENDING_CONFIRMATION),
    SOLD(TicketStatus.SOLD),
    COMPLIMENTARY(TicketStatus.COMPLIMENTARY),
    EXPIRED(TicketStatus.AVAILABLE),
    REJECTED(TicketStatus.AVAILABLE),
    CANCELLED(TicketStatus.AVAILABLE);

    private final TicketStatus ticketStatus;

    OrderStatus(TicketStatus ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    /** Status of the tickets held by an order in this status (one of the five business states, ADR-009). */
    public TicketStatus ticketStatus() {
        return ticketStatus;
    }

    public Set<OrderStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(RESERVED, REJECTED);
            case RESERVED -> EnumSet.of(PENDING_CONFIRMATION, EXPIRED, CANCELLED);
            case PENDING_CONFIRMATION -> EnumSet.of(SOLD, REJECTED);
            case SOLD, COMPLIMENTARY, EXPIRED, REJECTED, CANCELLED -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canTransitionTo(OrderStatus target) {
        return allowedTargets().contains(target);
    }

    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }
}
