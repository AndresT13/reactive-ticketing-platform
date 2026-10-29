package co.ticketing.web.error;

/** A purchase request arrived without the mandatory Idempotency-Key header (428). */
public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("The Idempotency-Key header is required for this operation");
    }
}
