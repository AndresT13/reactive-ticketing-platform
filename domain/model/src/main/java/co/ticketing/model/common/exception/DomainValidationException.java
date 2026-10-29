package co.ticketing.model.common.exception;

public final class DomainValidationException extends DomainException {

    public DomainValidationException(String message) {
        super("VALIDATION_ERROR", message);
    }
}
