package co.ticketing.model.ticket;

import co.ticketing.model.Fixtures;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.order.OrderId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TicketStatusTest {

    @Test
    void allowedTransitionsFollowTheBusinessRules() {
        assertThat(TicketStatus.AVAILABLE.canTransitionTo(TicketStatus.RESERVED)).isTrue();
        assertThat(TicketStatus.AVAILABLE.canTransitionTo(TicketStatus.COMPLIMENTARY)).isTrue();
        assertThat(TicketStatus.AVAILABLE.canTransitionTo(TicketStatus.SOLD)).isFalse();
        assertThat(TicketStatus.RESERVED.canTransitionTo(TicketStatus.PENDING_CONFIRMATION)).isTrue();
        assertThat(TicketStatus.RESERVED.canTransitionTo(TicketStatus.AVAILABLE)).isTrue();
        assertThat(TicketStatus.PENDING_CONFIRMATION.canTransitionTo(TicketStatus.SOLD)).isTrue();
        assertThat(TicketStatus.PENDING_CONFIRMATION.canTransitionTo(TicketStatus.AVAILABLE)).isTrue();
    }

    @Test
    void soldIsFinalIrreversibleAndTheOnlyAccountableSale() {
        assertThat(TicketStatus.SOLD.isFinal()).isTrue();
        assertThat(TicketStatus.SOLD.canTransitionTo(TicketStatus.AVAILABLE)).isFalse();
        assertThat(TicketStatus.SOLD.isAccountableSale()).isTrue();
        assertThat(TicketStatus.COMPLIMENTARY.isFinal()).isTrue();
        assertThat(TicketStatus.COMPLIMENTARY.isAccountableSale()).isFalse();
        assertThat(TicketStatus.RESERVED.isAccountableSale()).isFalse();
        assertThat(TicketStatus.PENDING_CONFIRMATION.isAccountableSale()).isFalse();
        assertThat(TicketStatus.AVAILABLE.isFinal()).isFalse();
    }

    @Test
    void ticketRequiresAllFields() {
        Ticket ticket = new Ticket(new TicketId("t-1"), Fixtures.EVENT_ID, new OrderId("o-1"), TicketStatus.RESERVED);

        assertThat(ticket.status()).isEqualTo(TicketStatus.RESERVED);
        assertThatThrownBy(() -> new Ticket(null, Fixtures.EVENT_ID, new OrderId("o-1"), TicketStatus.RESERVED))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new TicketId("bad id")).isInstanceOf(DomainValidationException.class);
    }
}
