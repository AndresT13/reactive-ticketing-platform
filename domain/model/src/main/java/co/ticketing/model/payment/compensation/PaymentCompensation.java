package co.ticketing.model.payment.compensation;

import java.time.Instant;
import java.util.Objects;

public record PaymentCompensation(
        String compensationId,
        String orderId,
        String authorizationId,
        CompensationStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant updatedAt,
        long version
) {
    public PaymentCompensation {
        Objects.requireNonNull(compensationId, "compensationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        Objects.requireNonNull(updatedAt, "updatedAt");

        if (compensationId.isBlank()
                || orderId.isBlank()
                || authorizationId.isBlank()) {
            throw new IllegalArgumentException(
                    "Compensation identifiers must not be blank");
        }
        if (attempts < 0 || version < 0) {
            throw new IllegalArgumentException(
                    "attempts and version cannot be negative");
        }
    }

    // Compatibility with the current DynamoDB adapter and existing tests.
    public PaymentCompensation(
            String compensationId,
            String orderId,
            String authorizationId,
            CompensationStatus status,
            int attempts,
            Instant nextAttemptAt,
            Instant updatedAt
    ) {
        this(compensationId, orderId, authorizationId, status,
                attempts, nextAttemptAt, updatedAt, 0);
    }

    public static PaymentCompensation pending(
            String orderId,
            String authorizationId,
            Instant now
    ) {
        return new PaymentCompensation(
                authorizationId,
                orderId,
                authorizationId,
                CompensationStatus.PENDING,
                0,
                now,
                now,
                0
        );
    }

    public PaymentCompensation startProcessing(Instant now) {
        requireState(CompensationStatus.PENDING);
        Objects.requireNonNull(now, "now");

        if (now.isBefore(nextAttemptAt)) {
            throw new IllegalStateException(
                    "Compensation is not due yet");
        }

        return transition(
                CompensationStatus.PROCESSING,
                attempts + 1,
                now,
                now
        );
    }

    public PaymentCompensation complete(Instant now) {
        requireState(CompensationStatus.PROCESSING);
        Objects.requireNonNull(now, "now");

        return transition(
                CompensationStatus.COMPLETED,
                attempts,
                nextAttemptAt,
                now
        );
    }

    public PaymentCompensation scheduleRetry(
            Instant nextAttempt,
            Instant now
    ) {
        requireState(CompensationStatus.PROCESSING);
        Objects.requireNonNull(nextAttempt, "nextAttempt");
        Objects.requireNonNull(now, "now");

        if (!nextAttempt.isAfter(now)) {
            throw new IllegalArgumentException(
                    "nextAttempt must be after now");
        }

        return transition(
                CompensationStatus.PENDING,
                attempts,
                nextAttempt,
                now
        );
    }

    public PaymentCompensation fail(Instant now) {
        requireState(CompensationStatus.PROCESSING);
        Objects.requireNonNull(now, "now");

        return transition(
                CompensationStatus.FAILED,
                attempts,
                nextAttemptAt,
                now
        );
    }

    private void requireState(CompensationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " but was " + status);
        }
    }

    private PaymentCompensation transition(
            CompensationStatus nextStatus,
            int nextAttempts,
            Instant nextAttempt,
            Instant now
    ) {
        if (now.isBefore(updatedAt)) {
            throw new IllegalArgumentException(
                    "Transition time cannot go backwards");
        }

        return new PaymentCompensation(
                compensationId,
                orderId,
                authorizationId,
                nextStatus,
                nextAttempts,
                nextAttempt,
                now,
                version + 1
        );
    }
}
