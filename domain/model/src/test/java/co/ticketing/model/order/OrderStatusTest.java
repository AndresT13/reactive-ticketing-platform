package co.ticketing.model.order;

import co.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void workflowTableMatchesTheProcess() {
        assertThat(OrderStatus.PENDING.allowedTargets()).containsExactlyInAnyOrder(OrderStatus.RESERVED, OrderStatus.REJECTED);
        assertThat(OrderStatus.RESERVED.allowedTargets())
                .containsExactlyInAnyOrder(OrderStatus.PENDING_CONFIRMATION, OrderStatus.EXPIRED, OrderStatus.CANCELLED);
        assertThat(OrderStatus.PENDING_CONFIRMATION.allowedTargets())
                .containsExactlyInAnyOrder(OrderStatus.SOLD, OrderStatus.REJECTED);
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.SOLD)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SOLD", "COMPLIMENTARY", "EXPIRED", "REJECTED", "CANCELLED"})
    void finalStatusesHaveNoExit(OrderStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @Test
    void mapsToTheFiveTicketStates() {
        assertThat(OrderStatus.PENDING.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(OrderStatus.RESERVED.ticketStatus()).isEqualTo(TicketStatus.RESERVED);
        assertThat(OrderStatus.PENDING_CONFIRMATION.ticketStatus()).isEqualTo(TicketStatus.PENDING_CONFIRMATION);
        assertThat(OrderStatus.SOLD.ticketStatus()).isEqualTo(TicketStatus.SOLD);
        assertThat(OrderStatus.COMPLIMENTARY.ticketStatus()).isEqualTo(TicketStatus.COMPLIMENTARY);
        assertThat(OrderStatus.EXPIRED.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(OrderStatus.REJECTED.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
        assertThat(OrderStatus.CANCELLED.ticketStatus()).isEqualTo(TicketStatus.AVAILABLE);
    }
}
