package co.ticketing.model.ticket;

import java.util.EnumSet;
import java.util.Set;

/**
 * The five ticket states defined by the business (State pattern).
 * The declaration order is relied upon by {@code Inventory} buckets: do not reorder.
 */
public enum TicketStatus {
    AVAILABLE,
    RESERVED,
    PENDING_CONFIRMATION,
    SOLD,
    COMPLIMENTARY;

    public Set<TicketStatus> allowedTargets() {
        return switch (this) {
            case AVAILABLE -> EnumSet.of(RESERVED, COMPLIMENTARY);
            case RESERVED -> EnumSet.of(PENDING_CONFIRMATION, AVAILABLE);
            case PENDING_CONFIRMATION -> EnumSet.of(SOLD, AVAILABLE);
            case SOLD, COMPLIMENTARY -> EnumSet.noneOf(TicketStatus.class);
        };
    }

    public boolean canTransitionTo(TicketStatus target) {
        return allowedTargets().contains(target);
    }

    /** SOLD is final and irreversible; COMPLIMENTARY is final but not accountable. */
    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }

    /** Only SOLD tickets count as revenue in accounting reports. */
    public boolean isAccountableSale() {
        return this == SOLD;
    }
}
