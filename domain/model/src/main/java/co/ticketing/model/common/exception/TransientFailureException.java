package co.ticketing.model.common.exception;

/**
 * Technical, retryable failure (throttling, transaction conflict, network timeout).
 * Driven adapters translate provider-specific errors into this type so that use cases
 * can apply a retry policy without knowing the underlying technology.
 */
public class TransientFailureException extends RuntimeException {

    public TransientFailureException(String message) {
        super(message);
    }

    public TransientFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
