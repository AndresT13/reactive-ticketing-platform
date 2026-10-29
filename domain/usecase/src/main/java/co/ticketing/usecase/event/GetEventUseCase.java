package co.ticketing.usecase.event;

import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.gateways.EventRepository;
import reactor.core.publisher.Mono;

public final class GetEventUseCase {

    private final EventRepository events;

    public GetEventUseCase(EventRepository events) {
        this.events = events;
    }

    public Mono<Event> execute(EventId eventId) {
        return events.findById(eventId)
                .switchIfEmpty(Mono.error(() -> new EventNotFoundException(eventId.value())));
    }
}
