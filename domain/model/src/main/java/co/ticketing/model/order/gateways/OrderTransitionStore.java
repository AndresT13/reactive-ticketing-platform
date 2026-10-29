package co.ticketing.model.order.gateways;

import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.OrderTransition;
import reactor.core.publisher.Mono;

/**
 * Applies a transition atomically: inventory movement (conditional), order (optimistic lock),
 * tickets, audit entry and guard release. Retryable technical failures are signalled as
 * {@link co.ticketing.model.common.exception.TransientFailureException}.
 */
public interface OrderTransitionStore {

    Mono<CommitResult> commit(OrderTransition transition);
}
