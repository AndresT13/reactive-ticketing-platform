package co.ticketing.messaging;

/**
 * Versioned wire format of an {@link co.ticketing.model.order.OrderCommand}. Timestamps travel as
 * ISO-8601 strings so the contract does not depend on serializer-specific date handling.
 */
public record CommandMessage(String type, int schemaVersion, String orderId, String eventId, Integer quantity,
                             String paymentToken, String occurredAt) {

    public static final String RESERVE_TICKETS = "ReserveTicketsCommand";
    public static final String PROCESS_PAYMENT = "ProcessPaymentCommand";
    public static final int CURRENT_SCHEMA_VERSION = 1;
}
