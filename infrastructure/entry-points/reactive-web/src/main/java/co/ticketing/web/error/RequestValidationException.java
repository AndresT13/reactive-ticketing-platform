package co.ticketing.web.error;

/** The HTTP request does not satisfy the API contract (400). */
public class RequestValidationException extends RuntimeException {

    public RequestValidationException(String message) {
        super(message);
    }
}
