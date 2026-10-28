package co.ticketing.model.order;

import co.ticketing.model.common.exception.DomainValidationException;

/** Number of tickets in an order. The upper bound is an anti-hoarding business rule. */
public record Quantity(int value) {

    public static final int MAX = 10;

    public Quantity {
        if (value < 1 || value > MAX) {
            throw new DomainValidationException("quantity must be between 1 and " + MAX);
        }
    }
}
