package co.ticketing.model.common;

import co.ticketing.model.common.exception.DomainValidationException;

import java.util.regex.Pattern;

/** Identity of the buyer. Taken from the {@code sub} claim of the access token (e.g. a Cognito UUID). */
public record CustomerId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_.@-]{1,128}");

    public CustomerId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new DomainValidationException("customerId must match [A-Za-z0-9_.@-]{1,128}");
        }
    }
}
