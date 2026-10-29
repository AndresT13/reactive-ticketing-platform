package co.ticketing.model.idempotency.gateways;

import co.ticketing.model.idempotency.IdempotencyRecord;
import reactor.core.publisher.Mono;

@FunctionalInterface
public interface IdempotencyStore {

    /** Completes empty when the key was never used (or already expired and was purged). */
    Mono<IdempotencyRecord> find(String scopedKey);
}
