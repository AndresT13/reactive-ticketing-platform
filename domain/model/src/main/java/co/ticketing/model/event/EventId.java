package co.ticketing.model.event;

import co.ticketing.model.common.Validations;

public record EventId(String value) {

    public EventId {
        Validations.requireIdentifier(value, "eventId");
    }
}
