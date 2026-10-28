package co.ticketing.model.common.exception;

/**
 * Root of the business exceptions. It is sealed so that every entry point can map
 * all business failures exhaustively with a pattern-matching {@code switch}.
 */
public abstract sealed class DomainException extends RuntimeException
        permits ActiveOrderExistsException, DomainValidationException, EventNotFoundException,
                IdempotencyKeyReusedException, InvalidStateTransitionException, OrderNotFoundException,
                ReservationExpiredException, SalesClosedException, SoldOutException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** Stable, machine-readable error code exposed to API clients. */
    public String code() {
        return code;
    }
}
