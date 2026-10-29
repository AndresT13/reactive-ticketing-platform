package co.ticketing.model.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.Validations;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.ReservationExpiredException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.InventoryMovement;
import co.ticketing.model.ticket.Ticket;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.model.ticket.TicketStatus;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * Aggregate root of the purchase process. Immutable: every business operation returns an
 * {@link OrderTransition} describing the before/after state, the inventory movement and the audit entry.
 * The domain decides; the persistence adapter applies the transition atomically.
 *
 * @param expiresAt   end of the reservation window (set once the tickets are RESERVED)
 * @param deadlineAt  next moment the order needs attention while it is not final; {@code null} when final
 */
public record Order(OrderId id, EventId eventId, CustomerId customerId, Quantity quantity, OrderStatus status,
                    List<TicketId> ticketIds, Instant expiresAt, Instant deadlineAt, RejectionReason failureReason,
                    long version, Instant createdAt, Instant updatedAt) {

    public static final String WORKER_ACTOR = "system:worker";
    public static final String SWEEPER_ACTOR = "system:sweeper";

    public Order {
        Validations.requirePresent(id, "id");
        Validations.requirePresent(eventId, "eventId");
        Validations.requirePresent(customerId, "customerId");
        Validations.requirePresent(quantity, "quantity");
        Validations.requirePresent(status, "status");
        Validations.requirePresent(createdAt, "createdAt");
        Validations.requirePresent(updatedAt, "updatedAt");
        ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
        if (!ticketIds.isEmpty() && ticketIds.size() != quantity.value()) {
            throw new IllegalStateException("Order " + id.value() + " must hold exactly " + quantity.value() + " tickets");
        }
    }

    // ---------------------------------------------------------------- factories

    /** A new purchase request: PENDING until a worker validates the real availability. */
    public static Order place(OrderId id, EventId eventId, CustomerId customerId, Quantity quantity,
                              Instant now, OrderPolicy policy) {
        return new Order(id, eventId, customerId, quantity, OrderStatus.PENDING, List.of(), null,
                now.plus(policy.pendingRedispatchAfter()), null, 0, now, now);
    }

    /** Courtesy tickets go straight from AVAILABLE to the final COMPLIMENTARY status. */
    public static OrderTransition issueComplimentary(OrderId id, EventId eventId, CustomerId recipient, Quantity quantity,
                                                     List<TicketId> ticketIds, CustomerId issuedBy, Instant now) {
        requireTicketIds(ticketIds, quantity);
        Order issued = new Order(id, eventId, recipient, quantity, OrderStatus.COMPLIMENTARY, ticketIds,
                null, null, null, 0, now, now);
        InventoryMovement movement = new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, quantity.value());
        return OrderTransition.of(null, issued, movement, "admin:" + issuedBy.value(), "COMPLIMENTARY_ISSUED", now);
    }

    // ---------------------------------------------------------------- workflow steps

    /** PENDING -> RESERVED: holds the tickets for {@code policy.reservationTtl()}. */
    public OrderTransition reserve(List<TicketId> newTicketIds, Instant now, OrderPolicy policy) {
        requireTicketIds(newTicketIds, quantity);
        Instant expiry = now.plus(policy.reservationTtl());
        Order reserved = next(OrderStatus.RESERVED, newTicketIds, expiry, expiry, null, now);
        return OrderTransition.of(this, reserved, movement(TicketStatus.AVAILABLE, TicketStatus.RESERVED),
                WORKER_ACTOR, "TICKETS_RESERVED", now);
    }

    /** RESERVED -> PENDING_CONFIRMATION: only the owner, and only before the reservation expires. */
    public OrderTransition requestConfirmation(CustomerId requester, Instant now, OrderPolicy policy) {
        requireOwner(requester);
        if (status == OrderStatus.RESERVED && !now.isBefore(expiresAt)) {
            throw new ReservationExpiredException(id.value());
        }
        Order confirming = next(OrderStatus.PENDING_CONFIRMATION, ticketIds, expiresAt,
                now.plus(policy.paymentTimeout()), null, now);
        return OrderTransition.of(this, confirming, movement(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION),
                customerActor(), "CONFIRMATION_REQUESTED", now);
    }

    /** PENDING_CONFIRMATION -> SOLD: final and irreversible. */
    public OrderTransition markSold(Instant now) {
        Order sold = next(OrderStatus.SOLD, ticketIds, expiresAt, null, null, now);
        return OrderTransition.of(this, sold, movement(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD),
                WORKER_ACTOR, "PAYMENT_APPROVED", now);
    }

    /**
     * PENDING -> REJECTED (no tickets were held) or PENDING_CONFIRMATION -> REJECTED
     * (compensation: the held tickets go back to AVAILABLE).
     */
    public OrderTransition reject(RejectionReason reason, String actor, Instant now) {
        Validations.requirePresent(reason, "reason");
        if (status == OrderStatus.PENDING) {
            Order rejected = next(OrderStatus.REJECTED, List.of(), null, null, reason, now);
            return OrderTransition.of(this, rejected, null, actor, reason.name(), now);
        }
        Order rejected = next(OrderStatus.REJECTED, ticketIds, expiresAt, null, reason, now);
        return OrderTransition.of(this, rejected, movement(TicketStatus.PENDING_CONFIRMATION, TicketStatus.AVAILABLE),
                actor, reason.name(), now);
    }

    /** RESERVED -> EXPIRED (compensation): the reservation window elapsed without confirmation. */
    public OrderTransition expire(Instant now) {
        if (status != OrderStatus.RESERVED) {
            throw invalid(OrderStatus.EXPIRED);
        }
        requireDue(now, OrderStatus.EXPIRED);
        Order expired = next(OrderStatus.EXPIRED, ticketIds, expiresAt, null, null, now);
        return OrderTransition.of(this, expired, movement(TicketStatus.RESERVED, TicketStatus.AVAILABLE),
                SWEEPER_ACTOR, "RESERVATION_EXPIRED", now);
    }

    /** PENDING_CONFIRMATION -> REJECTED when the payment did not settle in time. */
    public OrderTransition timeoutPayment(Instant now) {
        if (status != OrderStatus.PENDING_CONFIRMATION) {
            throw invalid(OrderStatus.REJECTED);
        }
        requireDue(now, OrderStatus.REJECTED);
        return reject(RejectionReason.PAYMENT_TIMEOUT, SWEEPER_ACTOR, now);
    }

    /** RESERVED -> CANCELLED: the owner releases the reservation voluntarily. */
    public OrderTransition cancel(CustomerId requester, Instant now) {
        requireOwner(requester);
        Order cancelled = next(OrderStatus.CANCELLED, ticketIds, expiresAt, null, null, now);
        return OrderTransition.of(this, cancelled, movement(TicketStatus.RESERVED, TicketStatus.AVAILABLE),
                customerActor(), "CANCELLED_BY_CUSTOMER", now);
    }

    // ---------------------------------------------------------------- queries

    public boolean isOwnedBy(CustomerId requester) {
        return customerId.equals(requester);
    }

    /** True when the order is not final and its deadline has been reached. */
    public boolean isDueAt(Instant now) {
        return deadlineAt != null && !deadlineAt.isAfter(now);
    }

    public Optional<Instant> reservationExpiry() {
        return Optional.ofNullable(expiresAt);
    }

    public Optional<RejectionReason> rejectionReason() {
        return Optional.ofNullable(failureReason);
    }

    public TicketStatus ticketStatus() {
        return status.ticketStatus();
    }

    public List<Ticket> tickets() {
        return ticketIds.stream()
                .map(ticketId -> new Ticket(ticketId, eventId, id, status.ticketStatus()))
                .toList();
    }

    // ---------------------------------------------------------------- internals

    private Order next(OrderStatus target, List<TicketId> tickets, Instant expiry, Instant deadline,
                       RejectionReason reason, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw invalid(target);
        }
        return new Order(id, eventId, customerId, quantity, target, tickets, expiry, deadline, reason,
                version + 1, createdAt, now);
    }

    private InventoryMovement movement(TicketStatus from, TicketStatus to) {
        return new InventoryMovement(from, to, quantity.value());
    }

    private String customerActor() {
        return "customer:" + customerId.value();
    }

    private void requireOwner(CustomerId requester) {
        if (!isOwnedBy(requester)) {
            throw new OrderNotFoundException(id.value());
        }
    }

    private void requireDue(Instant now, OrderStatus target) {
        if (!isDueAt(now)) {
            throw invalid(target);
        }
    }

    private InvalidStateTransitionException invalid(OrderStatus target) {
        return new InvalidStateTransitionException(id.value(), status.name(), target.name());
    }

    private static void requireTicketIds(List<TicketId> ticketIds, Quantity quantity) {
        if (ticketIds == null || ticketIds.size() != quantity.value() || new HashSet<>(ticketIds).size() != ticketIds.size()) {
            throw new IllegalArgumentException("Exactly " + quantity.value() + " distinct ticket ids are required");
        }
    }
}
