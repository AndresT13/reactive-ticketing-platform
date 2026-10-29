package co.ticketing.web.dto;

import co.ticketing.web.error.RequestValidationException;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Parsing helpers for request values that Bean Validation cannot type-check. */
public final class Requests {

    private Requests() {
    }

    public static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new RequestValidationException(field + " must be an ISO-8601 instant, e.g. 2026-12-12T20:00:00Z");
        }
    }

    public static int positiveInt(String value, String field) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 1) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new RequestValidationException(field + " must be a positive integer");
        }
    }
}
