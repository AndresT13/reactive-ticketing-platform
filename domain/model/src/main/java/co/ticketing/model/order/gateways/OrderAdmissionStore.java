package co.ticketing.model.order.gateways;

import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.order.AdmissionResult;
import co.ticketing.model.order.Order;
import reactor.core.publisher.Mono;

public interface OrderAdmissionStore {

    /** Atomically stores the PENDING order, its idempotency record and the active-order guard. */
    Mono<AdmissionResult> admit(Order order, IdempotencyRecord idempotencyRecord);
}
