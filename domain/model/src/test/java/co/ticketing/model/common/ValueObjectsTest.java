package co.ticketing.model.common;

import co.ticketing.model.audit.AuditEntry;
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
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.ReserveTicketsCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectsTest {

    @Test
    void identifiersAreValidated() {
        assertThat(new EventId("evt_1-a").value()).isEqualTo("evt_1-a");
        assertThatThrownBy(() -> new EventId(null)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderId("has space")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new OrderId("x".repeat(65))).isInstanceOf(DomainValidationException.class);
        assertThat(new CustomerId("2f1c6b1e-cognito-sub@example.com").value()).contains("@");
        assertThatThrownBy(() -> new CustomerId("")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new CustomerId(null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void quantityIsBetweenOneAndTen() {
        assertThat(new Quantity(1).value()).isEqualTo(1);
        assertThat(new Quantity(Quantity.MAX).value()).isEqualTo(10);
        assertThatThrownBy(() -> new Quantity(0)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Quantity(11)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void validationsHelpers() {
        assertThat(Validations.requireText("  text  ", "f", 10)).isEqualTo("text");
        assertThatThrownBy(() -> Validations.requireText(" ", "f", 10)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Validations.requireText(null, "f", 10)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Validations.requireText("toolong", "f", 3)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Validations.requirePresent(null, "f")).isInstanceOf(DomainValidationException.class);
        assertThat(Validations.requirePresent("v", "f")).isEqualTo("v");
    }

    @Test
    void pageCopiesItemsAndExposesCursor() {
        Page<String> page = new Page<>(List.of("a"), "next");
        Page<String> last = new Page<>(List.of(), null);

        assertThat(page.items()).containsExactly("a");
        assertThat(page.next()).contains("next");
        assertThat(last.next()).isEmpty();
    }

    @Test
    void orderPolicyDefaultsAndBuckets() {
        OrderPolicy policy = OrderPolicy.defaults();

        assertThat(policy.reservationTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.bucketOf(new OrderId("ord-1"))).isBetween(0, policy.deadlineBuckets() - 1);
        assertThat(policy.bucketOf(new OrderId("ord-1"))).isEqualTo(policy.bucketOf(new OrderId("ord-1")));
        assertThatThrownBy(() -> new OrderPolicy(Duration.ZERO, Duration.ofMinutes(5), Duration.ofMinutes(2), 8, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPolicy(Duration.ofMinutes(10), null, Duration.ofMinutes(2), 8, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(2), 0, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderPolicy(Duration.ofMinutes(10), Duration.ofMinutes(5), Duration.ofMinutes(-2), 8, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commandsValidateTheirFields() {
        Instant now = Instant.now();
        assertThat(new ReserveTicketsCommand(new OrderId("o"), new EventId("e"), new Quantity(1), now).orderId().value()).isEqualTo("o");
        assertThat(new ProcessPaymentCommand(new OrderId("o"), " tok_1 ", now).paymentToken()).isEqualTo("tok_1");
        assertThatThrownBy(() -> new ReserveTicketsCommand(null, new EventId("e"), new Quantity(1), now))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new ProcessPaymentCommand(new OrderId("o"), "", now)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void auditEntryNormalizesTicketIds() {
        AuditEntry entry = new AuditEntry("o", 1, null, "PENDING", 0, null, "actor", "reason", Instant.now());

        assertThat(entry.ticketIds()).isEmpty();
    }

    @Test
    void domainExceptionsExposeStableCodes() {
        List<DomainException> exceptions = List.of(
                new ActiveOrderExistsException("e"), new DomainValidationException("m"), new EventNotFoundException("e"),
                new IdempotencyKeyReusedException(), new InvalidStateTransitionException("o", "A", "B"),
                new OrderNotFoundException("o"), new ReservationExpiredException("o"), new SalesClosedException("e"),
                new SoldOutException("e", 2));

        assertThat(exceptions).extracting(DomainException::code).containsExactly(
                "ACTIVE_ORDER_EXISTS", "VALIDATION_ERROR", "EVENT_NOT_FOUND", "IDEMPOTENCY_KEY_REUSED",
                "INVALID_STATE_TRANSITION", "ORDER_NOT_FOUND", "RESERVATION_EXPIRED", "SALES_CLOSED", "SOLD_OUT");
        assertThat(new SoldOutException("e", 2)).hasMessageContaining("fewer than 2");
        assertThat(new TransientFailureException("t", new RuntimeException())).hasMessage("t");
        assertThat(new TransientFailureException("t")).hasNoCause();
    }
}
