package co.ticketing.model.common.exception;

public final class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(String aggregateId, String from, String to) {
        super("INVALID_STATE_TRANSITION", "Cannot move %s from %s to %s".formatted(aggregateId, from, to));
    }
}
