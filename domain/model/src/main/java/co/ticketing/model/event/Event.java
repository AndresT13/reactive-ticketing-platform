package co.ticketing.model.event;

import co.ticketing.model.common.Validations;
import co.ticketing.model.common.exception.DomainValidationException;

import java.time.Instant;

/** Aggregate root of the event catalog (supporting subdomain). Immutable. */
public record Event(EventId id, String name, Venue venue, Instant eventDate, int totalCapacity, Instant createdAt) {

    public static final int MAX_CAPACITY = 1_000_000;

    public Event {
        Validations.requirePresent(id, "id");
        name = Validations.requireText(name, "name", 200);
        Validations.requirePresent(venue, "venue");
        Validations.requirePresent(eventDate, "eventDate");
        Validations.requirePresent(createdAt, "createdAt");
        if (totalCapacity < 1 || totalCapacity > MAX_CAPACITY) {
            throw new DomainValidationException("totalCapacity must be between 1 and " + MAX_CAPACITY);
        }
    }

    /** Factory method: a new event must take place in the future. */
    public static Event create(EventId id, String name, Venue venue, Instant eventDate, int totalCapacity, Instant now) {
        Validations.requirePresent(eventDate, "eventDate");
        if (!eventDate.isAfter(now)) {
            throw new DomainValidationException("eventDate must be in the future");
        }
        return new Event(id, name, venue, eventDate, totalCapacity, now);
    }

    /** Tickets can only be sold before the event starts. */
    public boolean isOnSaleAt(Instant now) {
        return eventDate.isAfter(now);
    }
}
