package co.ticketing.model.payment.compensation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PaymentCompensationTransitionsTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T12:00:00Z");

    private PaymentCompensation pending() {
        return PaymentCompensation.pending("order-1", "auth-1", NOW);
    }

    @Test
    void startsProcessingAndIncrementsVersion() {
        var original = pending();
        var processing = original.startProcessing(NOW);

        assertEquals(CompensationStatus.PROCESSING, processing.status());
        assertEquals(1, processing.attempts());
        assertEquals(1, processing.version());
        assertEquals(CompensationStatus.PENDING, original.status());
    }

    @Test
    void completesProcessing() {
        var completed = pending()
                .startProcessing(NOW)
                .complete(NOW.plusSeconds(1));

        assertEquals(CompensationStatus.COMPLETED, completed.status());
        assertEquals(2, completed.version());
    }

    @Test
    void schedulesRetry() {
        var nextAttempt = NOW.plusSeconds(30);

        var retry = pending()
                .startProcessing(NOW)
                .scheduleRetry(nextAttempt, NOW.plusSeconds(1));

        assertEquals(CompensationStatus.PENDING, retry.status());
        assertEquals(1, retry.attempts());
        assertEquals(2, retry.version());
        assertEquals(nextAttempt, retry.nextAttemptAt());
    }

    @Test
    void permanentlyFailsProcessing() {
        var failed = pending()
                .startProcessing(NOW)
                .fail(NOW.plusSeconds(1));

        assertEquals(CompensationStatus.FAILED, failed.status());
        assertEquals(2, failed.version());
    }

    @Test
    void rejectsRepeatedCompletion() {
        var completed = pending()
                .startProcessing(NOW)
                .complete(NOW.plusSeconds(1));

        assertThrows(IllegalStateException.class,
                () -> completed.complete(NOW.plusSeconds(2)));
    }

    @Test
    void cannotProcessBeforeScheduledTime() {
        var compensation = pending();

        assertThrows(IllegalStateException.class,
                () -> compensation.startProcessing(NOW.minusSeconds(1)));
    }

    @Test
    void rejectsInvalidRetryDeadline() {
        var processing = pending().startProcessing(NOW);

        assertThrows(IllegalArgumentException.class,
                () -> processing.scheduleRetry(NOW, NOW));
    }

    @Test
    void rejectsNegativeVersion() {
        assertThrows(IllegalArgumentException.class,
                () -> new PaymentCompensation(
                        "auth-1", "order-1", "auth-1",
                        CompensationStatus.PENDING,
                        0, NOW, NOW, -1
                ));
    }
}
