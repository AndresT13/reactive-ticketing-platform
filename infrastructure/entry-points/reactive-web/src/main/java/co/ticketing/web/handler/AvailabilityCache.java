package co.ticketing.web.handler;

import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Micro-cache (Cache-Aside, ~1 s) shared by every reader of an event's availability: thousands of SSE
 * subscribers cost one DynamoDB read per second. It is never used to decide a sale; the conditional
 * write in the worker does that.
 */
public final class AvailabilityCache {

    private final GetAvailabilityUseCase getAvailability;
    private final Duration ttl;
    private final Cache<EventId, Mono<Inventory>> cache;

    public AvailabilityCache(GetAvailabilityUseCase getAvailability, Duration ttl) {
        this.getAvailability = getAvailability;
        this.ttl = ttl;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(10_000).build();
    }

    public Mono<Inventory> get(EventId eventId) {
        return Mono.defer(() -> cache.get(eventId, id -> getAvailability.execute(id)
                        .cache(value -> ttl, error -> Duration.ZERO, () -> Duration.ZERO)))
                .doOnError(error -> cache.invalidate(eventId));
    }
}
