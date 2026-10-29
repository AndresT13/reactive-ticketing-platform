package co.ticketing.web.dto;

import co.ticketing.usecase.event.CreateEventCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateEventRequest(@NotBlank @Size(max = 200) String name,
                                 @NotNull @Valid VenueRequest venue,
                                 @NotBlank String eventDate,
                                 @NotNull @Min(1) @Max(1_000_000) Integer totalCapacity) {

    public CreateEventCommand toCommand() {
        return new CreateEventCommand(name, venue.name(), venue.city(), Requests.instant(eventDate, "eventDate"), totalCapacity);
    }
}
