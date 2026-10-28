package co.ticketing.model.order;

/** Business outcome of committing an {@link OrderTransition}. Technical failures travel as errors instead. */
public sealed interface CommitResult {

    OrderTransition transition();

    /** Every condition held and all items were written atomically. */
    record Committed(OrderTransition transition) implements CommitResult {
    }

    /** The inventory bucket did not have enough tickets: the conditional write prevented an oversell. */
    record InsufficientInventory(OrderTransition transition) implements CommitResult {
    }

    /** The order changed concurrently or the step was already applied (duplicate message). */
    record StaleOrder(OrderTransition transition) implements CommitResult {
    }
}
