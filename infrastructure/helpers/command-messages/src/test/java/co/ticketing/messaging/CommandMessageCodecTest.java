package co.ticketing.messaging;

import co.ticketing.model.event.EventId;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.ReserveTicketsCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandMessageCodecTest {

    private static final Instant AT = Instant.parse("2026-10-27T20:00:00Z");
    private final CommandMessageCodec codec = new CommandMessageCodec();

    @Test
    void roundTripsAReservationCommand() {
        OrderCommand command = new ReserveTicketsCommand(new OrderId("o-1"), new EventId("e-1"), new Quantity(3), AT);

        String json = codec.encode(command);

        assertThat(json).contains("\"type\":\"ReserveTicketsCommand\"").contains("\"schemaVersion\":1");
        assertThat(codec.decode(json)).isEqualTo(command);
        assertThat(CommandMessageCodec.typeOf(command)).isEqualTo(CommandMessage.RESERVE_TICKETS);
    }

    @Test
    void roundTripsAPaymentCommand() {
        OrderCommand command = new ProcessPaymentCommand(new OrderId("o-1"), "tok_visa", AT);

        assertThat(codec.decode(codec.encode(command))).isEqualTo(command);
        assertThat(CommandMessageCodec.typeOf(command)).isEqualTo(CommandMessage.PROCESS_PAYMENT);
    }

    @Test
    void rejectsPoisonMessages() {
        assertThatThrownBy(() -> codec.decode("not json")).isInstanceOf(InvalidCommandMessageException.class);
        assertThatThrownBy(() -> codec.decode("{\"type\":\"Other\",\"schemaVersion\":1,\"orderId\":\"o\",\"occurredAt\":\"2026-10-27T20:00:00Z\"}"))
                .isInstanceOf(InvalidCommandMessageException.class);
        assertThatThrownBy(() -> codec.decode("{\"type\":\"ReserveTicketsCommand\",\"schemaVersion\":9,\"orderId\":\"o\",\"occurredAt\":\"2026-10-27T20:00:00Z\"}"))
                .isInstanceOf(InvalidCommandMessageException.class);
        assertThatThrownBy(() -> codec.decode("{\"schemaVersion\":1,\"orderId\":\"o\",\"occurredAt\":\"2026-10-27T20:00:00Z\"}"))
                .isInstanceOf(InvalidCommandMessageException.class);
    }
}
