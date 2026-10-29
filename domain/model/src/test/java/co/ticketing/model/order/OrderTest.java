package co.ticketing.model.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.ReservationExpiredException;
import co.ticketing.model.inventory.InventoryMovement;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static co.ticketing.model.Fixtures.CUSTOMER;
import static co.ticketing.model.Fixtures.EVENT_ID;
import static co.ticketing.model.Fixtures.NOW;
import static co.ticketing.model.Fixtures.OTHER_CUSTOMER;
import static co.ticketing.model.Fixtures.POLICY;
import static co.ticketing.model.Fixtures.pendingConfirmationOrder;
import static co.ticketing.model.Fixtures.pendingOrder;
import static co.ticketing.model.Fixtures.reservedOrder;
import static co.ticketing.model.Fixtures.ticketIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void placeCreatesPendingOrderWithRedispatchDeadline() {
        Order order = pendingOrder(2);

        assertThat(order.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(order.ticketIds()).isEmpty();
        assertThat(order.deadlineAt()).isEqualTo(NOW.plus(POLICY.pendingRedispatchAfter()));
        assertThat(order.version()).isZero();
        assertThat(order.reservationExpiry()).isEmpty();
        assertThat(order.rejectionReason()).isEmpty();
    }

    @Test
    void reserveHoldsTicketsForTenMinutes() {
        OrderTransition transition = pendingOrder(2).reserve(ticketIds(2), NOW, POLICY);
        Order reserved = transition.after();

        assertThat(reserved.status()).isEqualTo(OrderStatus.RESERVED);
        assertThat(reserved.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThat(reserved.deadlineAt()).isEqualTo(reserved.expiresAt());
        assertThat(reserved.version()).isEqualTo(1);
        assertThat(transition.movement()).isEqualTo(new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 2));
        assertThat(transition.createsTickets()).isTrue();
        assertThat(transition.affectedTickets()).hasSize(2)
                .allSatisfy(ticket -> assertThat(ticket.status()).isEqualTo(TicketStatus.RESERVED));
        assertThat(transition.audit().actor()).isEqualTo(Order.WORKER_ACTOR);
        assertThat(transition.audit().fromStatus()).isEqualTo("PENDING");
        assertThat(transition.audit().toStatus()).isEqualTo("RESERVED");
        assertThat(transition.releasesGuard()).isFalse();
    }

    @Test
    void reserveRequiresExactlyQuantityDistinctTickets() {
        Order order = pendingOrder(2);

        assertThatThrownBy(() -> order.reserve(ticketIds(1), NOW, POLICY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.reserve(List.of(new TicketId("t"), new TicketId("t")), NOW, POLICY))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> order.reserve(null, NOW, POLICY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reserveTwiceIsAnInvalidTransition() {
        Order reserved = reservedOrder(1);

        assertThatThrownBy(() -> reserved.reserve(ticketIds(1), NOW, POLICY))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void ownerConfirmsBeforeExpiry() {
        OrderTransition transition = reservedOrder(2).requestConfirmation(CUSTOMER, NOW.plusSeconds(30), POLICY);

        assertThat(transition.after().status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(transition.after().deadlineAt()).isEqualTo(NOW.plusSeconds(30).plus(POLICY.paymentTimeout()));
        assertThat(transition.movement().from()).isEqualTo(TicketStatus.RESERVED);
        assertThat(transition.movement().to()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(transition.createsTickets()).isFalse();
        assertThat(transition.audit().actor()).isEqualTo("customer:customer-1");
    }

    @Test
    void confirmationAfterExpiryFails() {
        Order reserved = reservedOrder(1);

        assertThatThrownBy(() -> reserved.requestConfirmation(CUSTOMER, reserved.expiresAt(), POLICY))
                .isInstanceOf(ReservationExpiredException.class);
    }

    @Test
    void anotherCustomerCannotSeeOrConfirmTheOrder() {
        Order reserved = reservedOrder(1);

        assertThatThrownBy(() -> reserved.requestConfirmation(OTHER_CUSTOMER, NOW, POLICY))
                .isInstanceOf(OrderNotFoundException.class);
        assertThatThrownBy(() -> reserved.cancel(OTHER_CUSTOMER, NOW)).isInstanceOf(OrderNotFoundException.class);
    }

    @Test
    void confirmingAPendingOrderIsInvalid() {
        assertThatThrownBy(() -> pendingOrder(1).requestConfirmation(CUSTOMER, NOW, POLICY))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void markSoldIsFinalAndReleasesGuard() {
        OrderTransition transition = pendingConfirmationOrder(2).markSold(NOW.plusSeconds(90));

        assertThat(transition.after().status()).isEqualTo(OrderStatus.SOLD);
        assertThat(transition.after().deadlineAt()).isNull();
        assertThat(transition.movement().to()).isEqualTo(TicketStatus.SOLD);
        assertThat(transition.releasesGuard()).isTrue();
        assertThatThrownBy(() -> transition.after().markSold(NOW)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void rejectingAPendingOrderMovesNoInventory() {
        OrderTransition transition = pendingOrder(3).reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, NOW);

        assertThat(transition.after().status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(transition.after().rejectionReason()).contains(RejectionReason.SOLD_OUT);
        assertThat(transition.inventoryMovement()).isEmpty();
        assertThat(transition.affectedTickets()).isEmpty();
        assertThat(transition.audit().quantity()).isZero();
        assertThat(transition.releasesGuard()).isTrue();
    }

    @Test
    void rejectingAfterPaymentReturnsTicketsToInventory() {
        OrderTransition transition = pendingConfirmationOrder(2)
                .reject(RejectionReason.PAYMENT_DECLINED, Order.WORKER_ACTOR, NOW.plusSeconds(90));

        assertThat(transition.movement())
                .isEqualTo(new InventoryMovement(TicketStatus.PENDING_CONFIRMATION, TicketStatus.AVAILABLE, 2));
        assertThat(transition.affectedTickets())
                .allSatisfy(ticket -> assertThat(ticket.status()).isEqualTo(TicketStatus.AVAILABLE));
    }

    @Test
    void rejectingAReservedOrderIsInvalid() {
        assertThatThrownBy(() -> reservedOrder(1).reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, NOW))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void expireOnlyWhenDue() {
        Order reserved = reservedOrder(2);

        assertThat(reserved.isDueAt(NOW)).isFalse();
        assertThatThrownBy(() -> reserved.expire(NOW)).isInstanceOf(InvalidStateTransitionException.class);

        OrderTransition transition = reserved.expire(reserved.expiresAt());
        assertThat(transition.after().status()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(transition.after().ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(transition.movement()).isEqualTo(new InventoryMovement(TicketStatus.RESERVED, TicketStatus.AVAILABLE, 2));
        assertThat(transition.audit().actor()).isEqualTo(Order.SWEEPER_ACTOR);
    }

    @Test
    void expireRequiresReservedStatus() {
        Order pending = pendingOrder(1);

        assertThatThrownBy(() -> pending.expire(NOW.plus(Duration.ofHours(1))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void paymentTimeoutReleasesTicketsWhenDue() {
        Order confirming = pendingConfirmationOrder(1);

        assertThatThrownBy(() -> confirming.timeoutPayment(NOW)).isInstanceOf(InvalidStateTransitionException.class);
        OrderTransition transition = confirming.timeoutPayment(confirming.deadlineAt());
        assertThat(transition.after().rejectionReason()).contains(RejectionReason.PAYMENT_TIMEOUT);
        assertThat(transition.movement().to()).isEqualTo(TicketStatus.AVAILABLE);
        assertThatThrownBy(() -> reservedOrder(1).timeoutPayment(NOW.plus(Duration.ofHours(1))))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void ownerCancelsReservation() {
        OrderTransition transition = reservedOrder(2).cancel(CUSTOMER, NOW.plusSeconds(5));

        assertThat(transition.after().status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(transition.movement().to()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(transition.releasesGuard()).isTrue();
    }

    @Test
    void complimentaryTicketsAreCreatedFinal() {
        OrderTransition transition = Order.issueComplimentary(new OrderId("cmp-1"), EVENT_ID, new CustomerId("vip"),
                new Quantity(2), ticketIds(2), new CustomerId("admin-1"), NOW);

        assertThat(transition.isCreation()).isTrue();
        assertThat(transition.after().status()).isEqualTo(OrderStatus.COMPLIMENTARY);
        assertThat(transition.after().status().isFinal()).isTrue();
        assertThat(transition.createsTickets()).isTrue();
        assertThat(transition.releasesGuard()).isFalse();
        assertThat(transition.audit().fromStatus()).isNull();
        assertThat(transition.audit().actor()).isEqualTo("admin:admin-1");
    }

    @Test
    void orderMustHoldExactlyQuantityTickets() {
        Order reserved = reservedOrder(2);

        assertThatThrownBy(() -> new Order(reserved.id(), reserved.eventId(), reserved.customerId(), reserved.quantity(),
                reserved.status(), ticketIds(1), reserved.expiresAt(), reserved.deadlineAt(), null, 1, NOW, NOW))
                .isInstanceOf(IllegalStateException.class);
        Order withNullTickets = new Order(reserved.id(), reserved.eventId(), reserved.customerId(), reserved.quantity(),
                OrderStatus.PENDING, null, null, null, null, 0, NOW, NOW);
        assertThat(withNullTickets.ticketIds()).isEmpty();
    }
}
