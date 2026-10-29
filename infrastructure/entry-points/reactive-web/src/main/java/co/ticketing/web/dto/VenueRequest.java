package co.ticketing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VenueRequest(@NotBlank @Size(max = 200) String name, @NotBlank @Size(max = 100) String city) {
}
