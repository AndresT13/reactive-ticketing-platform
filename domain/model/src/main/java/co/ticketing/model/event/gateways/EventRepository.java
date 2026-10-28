package co.ticketing.model.event.gateways;

import co.ticketing.model.common.Page;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import reactor.core.publisher.Mono;

public interface EventRepository {

    /** Emits the event, or completes empty when it does not exist. */
    Mono<Event> findById(EventId id);

    Mono<Page<Event>> findAll(int limit, String cursor);

    /** Persists the event and its initial inventory atomically. */
    Mono<Void> create(Event event, Inventory inventory);
}
