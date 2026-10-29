package co.ticketing.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ComplimentaryRequest(@NotBlank String recipientCustomerId, @NotNull @Min(1) @Max(10) Integer quantity) {
}
