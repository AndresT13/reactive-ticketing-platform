package co.ticketing.model.common.exception;

public final class ActiveOrderExistsException extends DomainException {

    public ActiveOrderExistsException(String eventId) {
        super("ACTIVE_ORDER_EXISTS", "Customer already has an active order for event " + eventId);
    }
}
