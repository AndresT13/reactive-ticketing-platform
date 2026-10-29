package co.ticketing.model.order;

import co.ticketing.model.audit.AuditEntry;
import co.ticketing.model.common.Validations;
import co.ticketing.model.inventory.InventoryMovement;
import co.ticketing.model.ticket.Ticket;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Everything that must change atomically for one step of the order workflow (Unit of Work):
 * the order itself, the inventory buckets, the affected tickets and the audit record.
 *
 * @param before   previous state, {@code null} when the transition creates the order
 * @param movement inventory movement, {@code null} when no tickets are involved (e.g. PENDING -> REJECTED)
 */
public record OrderTransition(Order before, Order after, InventoryMovement movement, AuditEntry audit) {

    public OrderTransition {
        Validations.requirePresent(after, "after");
        Validations.requirePresent(audit, "audit");
    }

    static OrderTransition of(Order before, Order after, InventoryMovement movement, String actor, String reason, Instant now) {
        AuditEntry audit = new AuditEntry(
                after.id().value(),
                after.version(),
                before == null ? null : before.status().name(),
                after.status().name(),
                movement == null ? 0 : movement.quantity(),
                after.ticketIds().stream().map(TicketId::value).toList(),
                actor,
                reason,
                now);
        return new OrderTransition(before, after, movement, audit);
    }

    public boolean isCreation() {
        return before == null;
    }

    public Optional<InventoryMovement> inventoryMovement() {
        return Optional.ofNullable(movement);
    }

    /** Tickets whose status changes in this transition, already in their new status. */
    public List<Ticket> affectedTickets() {
        return movement == null ? List.of() : after.tickets();
    }

    /** True when the tickets are materialized for the first time (they leave the AVAILABLE pool). */
    public boolean createsTickets() {
        return movement != null && movement.from() == TicketStatus.AVAILABLE;
    }

    /** True when the "one active order per customer and event" guard must be released. */
    public boolean releasesGuard() {
        return before != null && after.status().isFinal();
    }
}
