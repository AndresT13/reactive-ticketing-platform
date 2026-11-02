package co.ticketing.model.payment.authorization;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class PaymentAuthorizationTransitionsTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T20:00:00Z");

    @Test
    void pendingCanBeApproved() {
        var pending = PaymentAuthorization.pending("order-1", NOW);

        var approved = PaymentAuthorizationTransitions.approve(
                pending, "auth-1", NOW);

        assertThat(approved.status()).isEqualTo(
                PaymentAuthorization.Status.APPROVED);
        assertThat(approved.authorizationId()).isEqualTo("auth-1");
        assertThat(approved.version()).isEqualTo(1);
    }

    @Test
    void pendingCanBeDeclined() {
        var pending = PaymentAuthorization.pending("order-1", NOW);

        var declined = PaymentAuthorizationTransitions.decline(
                pending, "CARD_DECLINED", NOW);

        assertThat(declined.status()).isEqualTo(
                PaymentAuthorization.Status.DECLINED);
        assertThat(declined.version()).isEqualTo(1);
    }

    @Test
    void pendingCanEnterReconciliation() {
        var pending = PaymentAuthorization.pending("order-1", NOW);

        var result = PaymentAuthorizationTransitions.reconcile(
                pending, NOW);

        assertThat(result.status()).isEqualTo(
                PaymentAuthorization.Status.NEEDS_RECONCILIATION);
    }

    @Test
    void reconciliationCanBecomeApproved() {
        var pending = PaymentAuthorization.pending("order-1", NOW);
        var uncertain = PaymentAuthorizationTransitions.reconcile(
                pending, NOW);

        var approved = PaymentAuthorizationTransitions.approve(
                uncertain, "auth-1", NOW);

        assertThat(approved.status()).isEqualTo(
                PaymentAuthorization.Status.APPROVED);
        assertThat(approved.version()).isEqualTo(2);
    }

    @Test
    void approvedCannotBeDeclined() {
        var pending = PaymentAuthorization.pending("order-1", NOW);
        var approved = PaymentAuthorizationTransitions.approve(
                pending, "auth-1", NOW);

        assertThatThrownBy(() ->
                PaymentAuthorizationTransitions.decline(
                        approved, "CARD_DECLINED", NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void declinedCannotBeApproved() {
        var pending = PaymentAuthorization.pending("order-1", NOW);
        var declined = PaymentAuthorizationTransitions.decline(
                pending, "CARD_DECLINED", NOW);

        assertThatThrownBy(() ->
                PaymentAuthorizationTransitions.approve(
                        declined, "auth-1", NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
