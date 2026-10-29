package co.ticketing.usecase.event;

import co.ticketing.model.common.Page;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.gateways.EventRepository;
import reactor.core.publisher.Mono;

public final class ListEventsUseCase {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final EventRepository events;

    public ListEventsUseCase(EventRepository events) {
        this.events = events;
    }

    public Mono<Page<Event>> execute(int requestedLimit, String cursor) {
        int limit = requestedLimit <= 0 ? DEFAULT_LIMIT : Math.min(requestedLimit, MAX_LIMIT);
        return events.findAll(limit, cursor);
    }
}
