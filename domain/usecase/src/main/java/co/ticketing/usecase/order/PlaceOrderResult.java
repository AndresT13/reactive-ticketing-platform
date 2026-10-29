package co.ticketing.usecase.order;

import co.ticketing.model.order.Order;

/**
 * @param replayed   true when the response belongs to a previous request with the same Idempotency-Key
 * @param dispatched false when the command could not be published yet; the order is durable and the
 *                   housekeeping process will publish it again
 */
public record PlaceOrderResult(Order order, boolean replayed, boolean dispatched) {

    public static PlaceOrderResult accepted(Order order, boolean dispatched) {
        return new PlaceOrderResult(order, false, dispatched);
    }

    public static PlaceOrderResult replayed(Order order) {
        return new PlaceOrderResult(order, true, true);
    }
}
