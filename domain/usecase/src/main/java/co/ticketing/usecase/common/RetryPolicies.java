package co.ticketing.usecase.common;

import co.ticketing.model.common.exception.TransientFailureException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.function.Function;

/**
 * Reusable retry operators (Decorator + function composition). Applied with {@code Mono.transform}.
 * Only technical, transient failures are retried; business outcomes never are.
 */
public final class RetryPolicies {

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofMillis(50);
    private static final Duration MAX_BACKOFF = Duration.ofMillis(400);

    private RetryPolicies() {
    }

    public static <T> Function<Mono<T>, Mono<T>> onTransientFailure() {
        return mono -> mono.retryWhen(Retry.backoff(MAX_ATTEMPTS, FIRST_BACKOFF)
                .maxBackoff(MAX_BACKOFF)
                .jitter(0.5)
                .filter(TransientFailureException.class::isInstance)
                .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
    }
}
