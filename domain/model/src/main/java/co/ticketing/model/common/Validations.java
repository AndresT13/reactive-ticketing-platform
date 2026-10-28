package co.ticketing.model.common;

import co.ticketing.model.common.exception.DomainValidationException;

import java.util.regex.Pattern;

/** Small, dependency-free validation helpers shared by value objects and entities. */
public final class Validations {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private Validations() {
    }

    public static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new DomainValidationException(field + " must match [A-Za-z0-9_-]{1,64}");
        }
        return value;
    }

    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new DomainValidationException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new DomainValidationException(field + " must have at most " + maxLength + " characters");
        }
        return stripped;
    }

    public static <T> T requirePresent(T value, String field) {
        if (value == null) {
            throw new DomainValidationException(field + " is required");
        }
        return value;
    }
}
