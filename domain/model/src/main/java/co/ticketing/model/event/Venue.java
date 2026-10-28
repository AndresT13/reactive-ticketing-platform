package co.ticketing.model.event;

import co.ticketing.model.common.Validations;

public record Venue(String name, String city) {

    public Venue {
        name = Validations.requireText(name, "venue.name", 200);
        city = Validations.requireText(city, "venue.city", 100);
    }
}
