package co.ticketing.payment;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.payment.refund.RefundResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedPaymentRefundGatewayTest {

    private final SimulatedPaymentRefundGateway gateway =
            new SimulatedPaymentRefundGateway(Duration.ZERO);

    @Test
    void refundsApprovedAuthorization() {
        StepVerifier.create(gateway.refund("auth-123", "refund:auth-123"))
                .expectNext(new RefundResult.Refunded("refund-auth-123"))
                .verifyComplete();
    }

    @Test
    void repeatedRequestProducesSameRefundReference() {
        var first = gateway.refund("auth-123", "refund:auth-123").block();
        var second = gateway.refund("auth-123", "refund:auth-123").block();

        assertEquals(first, second);
    }

    @Test
    void providerOutageIsTransient() {
        StepVerifier.create(
                gateway.refund("auth-unavailable-123", "refund:123"))
                .expectError(TransientFailureException.class)
                .verify();
    }

    @Test
    void manualReviewDoesNotReportSuccessfulRefund() {
        StepVerifier.create(gateway.refund("auth-review-123", "refund:123"))
                .expectNext(new RefundResult.RequiresManualReview(
                        "Refund requires manual reconciliation"))
                .verifyComplete();
    }

    @Test
    void rejectsEmptyIdempotencyKey() {
        StepVerifier.create(gateway.refund("auth-123", ""))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void rejectsNegativeLatency() {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedPaymentRefundGateway(
                        Duration.ofSeconds(-1)));
    }
}
