package co.ticketing.dynamodb.mapper;

import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.Venue;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;

import static co.ticketing.dynamodb.mapper.Attributes.instant;
import static co.ticketing.dynamodb.mapper.Attributes.integer;
import static co.ticketing.dynamodb.mapper.Attributes.n;
import static co.ticketing.dynamodb.mapper.Attributes.s;
import static co.ticketing.dynamodb.mapper.Attributes.string;

public final class EventItemMapper {

    public static final String KEY = "eventId";

    private EventItemMapper() {
    }

    public static Map<String, AttributeValue> key(EventId eventId) {
        return Map.of(KEY, s(eventId.value()));
    }

    public static Map<String, AttributeValue> toItem(Event event) {
        return Map.of(
                KEY, s(event.id().value()),
                "name", s(event.name()),
                "venueName", s(event.venue().name()),
                "venueCity", s(event.venue().city()),
                "eventDate", instant(event.eventDate()),
                "totalCapacity", n(event.totalCapacity()),
                "createdAt", instant(event.createdAt()));
    }

    public static Event toDomain(Map<String, AttributeValue> item) {
        return new Event(new EventId(string(item, KEY)), string(item, "name"),
                new Venue(string(item, "venueName"), string(item, "venueCity")),
                instant(item, "eventDate"), integer(item, "totalCapacity"), instant(item, "createdAt"));
    }
}
