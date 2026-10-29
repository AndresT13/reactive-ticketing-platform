package co.ticketing.usecase.event;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.Venue;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.inventory.Inventory;
import reactor.core.publisher.Mono;

import java.time.Clock;

/** Creates an event together with its initial inventory (synchronous operation). */
public final class CreateEventUseCase {

    private final EventRepository events;
    private final IdGenerator ids;
    private final Clock clock;

    public CreateEventUseCase(EventRepository events, IdGenerator ids, Clock clock) {
        this.events = events;
        this.ids = ids;
        this.clock = clock;
    }

    public Mono<Event> execute(CreateEventCommand command) {
        return Mono.fromCallable(() -> Event.create(new EventId(ids.newId()), command.name(),
                        new Venue(command.venueName(), command.venueCity()), command.eventDate(),
                        command.totalCapacity(), clock.instant()))
                .flatMap(event -> events.create(event, Inventory.initial(event.id(), event.totalCapacity()))
                        .thenReturn(event));
    }
}
