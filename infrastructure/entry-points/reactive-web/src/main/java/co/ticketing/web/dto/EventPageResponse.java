package co.ticketing.web.dto;

import co.ticketing.model.common.Page;
import co.ticketing.model.event.Event;

import java.util.List;

public record EventPageResponse(List<EventResponse> items, String nextCursor) {

    public static EventPageResponse from(Page<Event> page) {
        return new EventPageResponse(page.items().stream().map(EventResponse::from).toList(), page.nextCursor());
    }
}
