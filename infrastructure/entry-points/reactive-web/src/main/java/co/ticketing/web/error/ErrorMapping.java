package co.ticketing.web.error;

import co.ticketing.model.common.exception.ActiveOrderExistsException;
import co.ticketing.model.common.exception.DomainException;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.IdempotencyKeyReusedException;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.ReservationExpiredException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.common.exception.TransientFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Duration;
import java.util.Locale;

/**
 * Maps every failure to an HTTP problem. Domain exceptions are a sealed hierarchy, so the switch is
 * exhaustive: adding a new business error without mapping it does not compile.
 */
public final class ErrorMapping {

    private static final Duration RETRY_AFTER = Duration.ofSeconds(2);

    private ErrorMapping() {
    }

    public static ApiError of(Throwable error) {
        return switch (error) {
            case DomainException domain -> fromDomain(domain);
            case RequestValidationException invalid ->
                    new ApiError(400, "VALIDATION_ERROR", "Invalid request", invalid.getMessage(), null);
            case IdempotencyKeyRequiredException missing ->
                    new ApiError(428, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required", missing.getMessage(), null);
            case ServerWebInputException _ ->
                    new ApiError(400, "VALIDATION_ERROR", "Malformed request", "The request body or parameters are malformed", null);
            case ResponseStatusException status -> fromStatus(status);
            case TransientFailureException _ -> new ApiError(503, "SERVICE_UNAVAILABLE", "Service temporarily unavailable",
                    "The service is temporarily unavailable, please retry", RETRY_AFTER);
            default -> new ApiError(500, "INTERNAL_ERROR", "Internal error", "An unexpected error occurred", null);
        };
    }

    private static ApiError fromDomain(DomainException error) {
        int status = switch (error) {
            case EventNotFoundException _, OrderNotFoundException _ -> 404;
            case DomainValidationException _ -> 400;
            case IdempotencyKeyReusedException _ -> 422;
            case ActiveOrderExistsException _, InvalidStateTransitionException _, ReservationExpiredException _,
                 SalesClosedException _, SoldOutException _ -> 409;
        };
        return new ApiError(status, error.code(), title(error.code()), error.getMessage(), null);
    }

    private static ApiError fromStatus(ResponseStatusException error) {
        int value = error.getStatusCode().value();
        HttpStatus status = HttpStatus.resolve(value);
        String code = status == null ? "HTTP_" + value : status.name();
        String title = status == null ? "Error" : status.getReasonPhrase();
        return new ApiError(value, code, title, error.getReason(), null);
    }

    /** SOLD_OUT -> "Sold out". */
    static String title(String code) {
        String words = code.replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
