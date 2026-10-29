package co.ticketing.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The payment token is issued by the payment provider's client SDK; card data never reaches this API. */
public record ConfirmOrderRequest(@NotBlank @Size(max = 128) String paymentToken) {
}
