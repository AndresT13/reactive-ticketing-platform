package co.ticketing.model.common.exception;

/**
 * Also raised when the order exists but belongs to another customer, so that
 * the API never reveals the existence of other customers' orders.
 */
public final class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String orderId) {
        super("ORDER_NOT_FOUND", "Order " + orderId + " was not found");
    }
}
