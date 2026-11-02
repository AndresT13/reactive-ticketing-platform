package co.ticketing.model.payment.compensation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PaymentCompensationTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T12:00:00Z");

    @Test
    void createsPendingCompensation() {
        PaymentCompensation compensation =
                PaymentCompensation.pending(
                        "order-123",
                        "auth-123",
                        NOW
                );

        assertEquals("auth-123", compensation.compensationId());
        assertEquals("order-123", compensation.orderId());
        assertEquals("auth-123", compensation.authorizationId());
        assertEquals(CompensationStatus.PENDING, compensation.status());
        assertEquals(0, compensation.attempts());
        assertEquals(NOW, compensation.nextAttemptAt());
        assertEquals(NOW, compensation.updatedAt());
    }

    @Test
    void rejectsNegativeAttempts() {
        assertThrows(IllegalArgumentException.class, () ->
                new PaymentCompensation(
                        "auth-123",
                        "order-123",
                        "auth-123",
                        CompensationStatus.PENDING,
                        -1,
                        NOW,
                        NOW
                )
        );
    }

    @Test
    void rejectsBlankIdentifiers() {
        assertThrows(IllegalArgumentException.class, () ->
                PaymentCompensation.pending(
                        "",
                        "auth-123",
                        NOW
                )
        );
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(NullPointerException.class, () ->
                new PaymentCompensation(
                        "auth-123",
                        "order-123",
                        "auth-123",
                        null,
                        0,
                        NOW,
                        NOW
                )
        );
    }

    @Test
    void usesDeterministicCompensationId() {
        PaymentCompensation first =
                PaymentCompensation.pending("order-123", "auth-123", NOW);

        PaymentCompensation second =
                PaymentCompensation.pending("order-123", "auth-123", NOW);

        assertEquals(first.compensationId(), second.compensationId());
    }
}
