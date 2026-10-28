package co.ticketing.usecase.availability;

import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import reactor.core.publisher.Mono;

/** Real-time availability, counting both sold and temporarily held tickets (RF-07). */
public final class GetAvailabilityUseCase {

    private final InventoryRepository inventories;

    public GetAvailabilityUseCase(InventoryRepository inventories) {
        this.inventories = inventories;
    }

    public Mono<Inventory> execute(EventId eventId) {
        return inventories.findByEventId(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId.value())));
    }
}
