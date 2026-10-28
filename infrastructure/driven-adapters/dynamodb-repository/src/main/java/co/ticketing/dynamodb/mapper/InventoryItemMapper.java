package co.ticketing.dynamodb.mapper;

import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.ticket.TicketStatus;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.Map;

import static co.ticketing.dynamodb.mapper.Attributes.instant;
import static co.ticketing.dynamodb.mapper.Attributes.integer;
import static co.ticketing.dynamodb.mapper.Attributes.n;
import static co.ticketing.dynamodb.mapper.Attributes.number;
import static co.ticketing.dynamodb.mapper.Attributes.s;
import static co.ticketing.dynamodb.mapper.Attributes.string;

public final class InventoryItemMapper {

    public static final String KEY = "eventId";

    private InventoryItemMapper() {
    }

    /** Attribute that holds the counter of a ticket status (the "bucket" moved by an InventoryMovement). */
    public static String bucketAttribute(TicketStatus status) {
        return switch (status) {
            case AVAILABLE -> "available";
            case RESERVED -> "reserved";
            case PENDING_CONFIRMATION -> "pendingConfirmation";
            case SOLD -> "sold";
            case COMPLIMENTARY -> "complimentary";
        };
    }

    public static Map<String, AttributeValue> key(EventId eventId) {
        return Map.of(KEY, s(eventId.value()));
    }

    public static Map<String, AttributeValue> toItem(Inventory inventory, Instant now) {
        return Map.of(
                KEY, s(inventory.eventId().value()),
                "totalCapacity", n(inventory.totalCapacity()),
                "available", n(inventory.available()),
                "reserved", n(inventory.reserved()),
                "pendingConfirmation", n(inventory.pendingConfirmation()),
                "sold", n(inventory.sold()),
                "complimentary", n(inventory.complimentary()),
                "version", n(inventory.version()),
                "updatedAt", instant(now));
    }

    public static Inventory toDomain(Map<String, AttributeValue> item) {
        return new Inventory(new EventId(string(item, KEY)), integer(item, "totalCapacity"),
                integer(item, "available"), integer(item, "reserved"), integer(item, "pendingConfirmation"),
                integer(item, "sold"), integer(item, "complimentary"), number(item, "version"));
    }
}
