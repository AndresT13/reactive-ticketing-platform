package co.ticketing.messaging;

import co.ticketing.model.event.EventId;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.ReserveTicketsCommand;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/** Translates domain commands to and from their JSON message representation. */
public final class CommandMessageCodec {

    private final JsonMapper mapper;

    public CommandMessageCodec() {
        this(JsonMapper.builder().build());
    }

    public CommandMessageCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public String encode(OrderCommand command) {
        CommandMessage message = switch (command) {
            case ReserveTicketsCommand(var orderId, var eventId, var quantity, var occurredAt) ->
                    new CommandMessage(CommandMessage.RESERVE_TICKETS, CommandMessage.CURRENT_SCHEMA_VERSION,
                            orderId.value(), eventId.value(), quantity.value(), null, occurredAt.toString());
            case ProcessPaymentCommand(var orderId, var paymentToken, var occurredAt) ->
                    new CommandMessage(CommandMessage.PROCESS_PAYMENT, CommandMessage.CURRENT_SCHEMA_VERSION,
                            orderId.value(), null, null, paymentToken, occurredAt.toString());
        };
        return mapper.writeValueAsString(message);
    }

    public static String typeOf(OrderCommand command) {
        return switch (command) {
            case ReserveTicketsCommand _ -> CommandMessage.RESERVE_TICKETS;
            case ProcessPaymentCommand _ -> CommandMessage.PROCESS_PAYMENT;
        };
    }

    public OrderCommand decode(String body) {
        try {
            CommandMessage message = mapper.readValue(body, CommandMessage.class);
            if (message.schemaVersion() != CommandMessage.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported schema version " + message.schemaVersion());
            }
            Instant occurredAt = Instant.parse(message.occurredAt());
            return switch (message.type()) {
                case CommandMessage.RESERVE_TICKETS -> new ReserveTicketsCommand(new OrderId(message.orderId()),
                        new EventId(message.eventId()), new Quantity(message.quantity()), occurredAt);
                case CommandMessage.PROCESS_PAYMENT -> new ProcessPaymentCommand(new OrderId(message.orderId()),
                        message.paymentToken(), occurredAt);
                case null, default -> throw new IllegalArgumentException("Unknown command type " + message.type());
            };
        } catch (RuntimeException e) {   // includes Jackson 3's unchecked JacksonException
            throw new InvalidCommandMessageException("Cannot decode command message", e);
        }
    }
}
