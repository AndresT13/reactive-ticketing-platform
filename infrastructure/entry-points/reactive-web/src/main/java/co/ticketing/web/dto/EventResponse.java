package co.ticketing.web.dto;

import co.ticketing.model.event.Event;

public record EventResponse(String id, String name, VenueResponse venue, String eventDate, int totalCapacity, String createdAt) {

    public record VenueResponse(String name, String city) {
    }

    public static EventResponse from(Event event) {
        return new EventResponse(event.id().value(), event.name(),
                new VenueResponse(event.venue().name(), event.venue().city()),
                event.eventDate().toString(), event.totalCapacity(), event.createdAt().toString());
    }
}
