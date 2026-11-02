package co.ticketing.model.payment.authorization;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAuthorizationTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T20:00:00Z");

    @Test
    void createsPendingAuthorization() {
        var authorization =
                PaymentAuthorization.pending("order-1", NOW);

        assertThat(authorization.orderId()).isEqualTo("order-1");
        assertThat(authorization.status())
                .isEqualTo(PaymentAuthorization.Status.PENDING);
        assertThat(authorization.authorizationId()).isNull();
        assertThat(authorization.version()).isZero();
    }

    @Test
    void approvedRequiresAuthorizationId() {
        assertThatThrownBy(() ->
                new PaymentAuthorization(
                        "order-1",
                        PaymentAuthorization.Status.APPROVED,
                        null,
                        null,
                        NOW,
                        1
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void declinedRequiresReason() {
        assertThatThrownBy(() ->
                new PaymentAuthorization(
                        "order-1",
                        PaymentAuthorization.Status.DECLINED,
                        null,
                        null,
                        NOW,
                        1
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidVersion() {
        assertThatThrownBy(() ->
                new PaymentAuthorization(
                        "order-1",
                        PaymentAuthorization.Status.PENDING,
                        null,
                        null,
                        NOW,
                        -1
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsReconciliationState() {
        var authorization = new PaymentAuthorization(
                "order-1",
                PaymentAuthorization.Status.NEEDS_RECONCILIATION,
                null,
                null,
                NOW,
                2
        );

        assertThat(authorization.status())
                .isEqualTo(
                        PaymentAuthorization.Status.NEEDS_RECONCILIATION);
    }
}
