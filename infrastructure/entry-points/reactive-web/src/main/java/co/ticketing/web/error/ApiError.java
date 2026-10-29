package co.ticketing.web.error;

import java.time.Duration;

/** Transport-level view of a failure: HTTP status, stable code and optional Retry-After. */
public record ApiError(int status, String code, String title, String detail, Duration retryAfter) {
}
