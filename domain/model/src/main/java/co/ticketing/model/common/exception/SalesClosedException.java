package co.ticketing.model.common.exception;

public final class SalesClosedException extends DomainException {

    public SalesClosedException(String eventId) {
        super("SALES_CLOSED", "Ticket sales for event " + eventId + " are closed");
    }
}
