package co.ticketing.model.idempotency;

import co.ticketing.model.common.exception.DomainValidationException;

import java.util.regex.Pattern;

/** Client-generated key (typically a UUID) that makes a purchase request safe to retry. */
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("Idempotency-Key must match [A-Za-z0-9_-]{8,128}");
        }
    }
}
