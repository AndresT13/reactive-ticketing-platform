package co.ticketing.web.dto;

import co.ticketing.model.inventory.Inventory;

/** {@code held} = reserved + pendingConfirmation: retained tickets that are not sales. */
public record AvailabilityResponse(String eventId, int totalCapacity, int available, int held, int reserved,
                                   int pendingConfirmation, int sold, int complimentary) {

    public static AvailabilityResponse from(Inventory inventory) {
        return new AvailabilityResponse(inventory.eventId().value(), inventory.totalCapacity(), inventory.available(),
                inventory.held(), inventory.reserved(), inventory.pendingConfirmation(), inventory.sold(),
                inventory.complimentary());
    }
}
