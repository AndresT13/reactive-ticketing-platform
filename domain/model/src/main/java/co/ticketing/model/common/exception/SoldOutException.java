package co.ticketing.model.common.exception;

public final class SoldOutException extends DomainException {

    public SoldOutException(String eventId, int requested) {
        // Java 25 (JEP 513): statements are allowed before the explicit super(...) call.
        String message = "Event %s has fewer than %d tickets available".formatted(eventId, requested);
        super("SOLD_OUT", message);
    }
}
