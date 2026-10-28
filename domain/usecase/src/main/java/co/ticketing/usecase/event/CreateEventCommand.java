package co.ticketing.usecase.event;

import java.time.Instant;

public record CreateEventCommand(String name, String venueName, String venueCity, Instant eventDate, int totalCapacity) {
}
