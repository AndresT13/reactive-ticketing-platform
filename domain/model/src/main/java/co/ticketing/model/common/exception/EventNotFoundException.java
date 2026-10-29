package co.ticketing.model.common.exception;

public final class EventNotFoundException extends DomainException {

    public EventNotFoundException(String eventId) {
        super("EVENT_NOT_FOUND", "Event " + eventId + " was not found");
    }
}
