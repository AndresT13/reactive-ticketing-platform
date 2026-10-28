package co.ticketing.model.inventory.gateways;

import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import reactor.core.publisher.Mono;

public interface InventoryRepository {

    /** Strongly consistent read. Completes empty when the event does not exist. */
    Mono<Inventory> findByEventId(EventId eventId);
}
