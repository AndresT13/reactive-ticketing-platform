package co.ticketing.model.order;

import co.ticketing.model.common.Validations;

public record OrderId(String value) {

    public OrderId {
        Validations.requireIdentifier(value, "orderId");
    }
}
