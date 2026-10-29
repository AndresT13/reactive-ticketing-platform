package co.ticketing.model.order;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.idempotency.IdempotencyRecord;

/** Outcome of persisting a new PENDING order together with its idempotency record and guard. */
public sealed interface AdmissionResult {

    record Admitted(Order order) implements AdmissionResult {
    }

    /** The same Idempotency-Key was stored concurrently; the caller decides between replay and rejection. */
    record IdempotencyConflict(IdempotencyRecord existing) implements AdmissionResult {
    }

    /** The customer already has a non-final order for the event. */
    record ActiveOrderExists(EventId eventId, CustomerId customerId) implements AdmissionResult {
    }
}
