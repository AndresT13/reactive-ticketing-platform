package co.ticketing.model.inventory;

import co.ticketing.model.common.Validations;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketStatus;

/**
 * Distribution of an event's capacity by ticket status: the materialized view of ticket states
 * and the concurrency-control point that prevents overselling.
 *
 * <p>Invariant: {@code available + reserved + pendingConfirmation + sold + complimentary == totalCapacity}
 * and every bucket is non-negative.
 */
public record Inventory(EventId eventId, int totalCapacity, int available, int reserved,
                        int pendingConfirmation, int sold, int complimentary, long version) {

    public Inventory {
        Validations.requirePresent(eventId, "eventId");
        if (available < 0 || reserved < 0 || pendingConfirmation < 0 || sold < 0 || complimentary < 0) {
            throw new IllegalStateException("Inventory buckets must not be negative for event " + eventId.value());
        }
        if (available + reserved + pendingConfirmation + sold + complimentary != totalCapacity) {
            throw new IllegalStateException("Inventory invariant violated for event " + eventId.value());
        }
    }

    public static Inventory initial(EventId eventId, int totalCapacity) {
        return new Inventory(eventId, totalCapacity, totalCapacity, 0, 0, 0, 0, 0);
    }

    public int count(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> available;
            case RESERVED -> reserved;
            case PENDING_CONFIRMATION -> pendingConfirmation;
            case SOLD -> sold;
            case COMPLIMENTARY -> complimentary;
        };
    }

    /** Tickets retained but not sold yet. */
    public int held() {
        return reserved + pendingConfirmation;
    }

    public boolean canAccommodate(Quantity quantity) {
        return available >= quantity.value();
    }

    /** Applies a movement, returning a new instance. Fails instead of letting a bucket go negative. */
    public Inventory apply(InventoryMovement movement) {
        if (count(movement.from()) < movement.quantity()) {
            if (movement.from() == TicketStatus.AVAILABLE) {
                throw new SoldOutException(eventId.value(), movement.quantity());
            }
            throw new InvalidStateTransitionException(eventId.value(), movement.from().name(), movement.to().name());
        }
        int[] buckets = {available, reserved, pendingConfirmation, sold, complimentary};
        buckets[movement.from().ordinal()] -= movement.quantity();
        buckets[movement.to().ordinal()] += movement.quantity();
        return new Inventory(eventId, totalCapacity, buckets[0], buckets[1], buckets[2], buckets[3], buckets[4], version + 1);
    }
}
