package co.ticketing.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** There is intentionally no customerId field: the buyer is always the authenticated subject. */
public record PlaceOrderRequest(@NotBlank String eventId, @NotNull @Min(1) @Max(10) Integer quantity) {
}
