package co.ticketing.web.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Lock-free token bucket per client key (Throttling pattern). Buckets live in a bounded cache, so an
 * attacker rotating keys cannot exhaust memory. This is a per-instance limit; the global limit is
 * enforced at the edge (WAF rate-based rules and API Gateway throttling).
 */
public final class TokenBucketRateLimiter {

    private record Bucket(double tokens, long refilledAtNanos) {
    }

    private final Cache<String, AtomicReference<Bucket>> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();
    private final int capacity;
    private final double refillPerNano;
    private final LongSupplier nanoClock;

    public TokenBucketRateLimiter(int capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refill rate must be positive");
        }
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(String key) {
        AtomicReference<Bucket> reference = buckets.get(key, ignored -> new AtomicReference<>(new Bucket(capacity, nanoClock.getAsLong())));
        while (true) {
            Bucket current = reference.get();
            long now = nanoClock.getAsLong();
            double available = Math.min(capacity, current.tokens() + (now - current.refilledAtNanos()) * refillPerNano);
            boolean granted = available >= 1;
            Bucket next = new Bucket(granted ? available - 1 : available, now);
            if (reference.compareAndSet(current, next)) {
                return granted;
            }
        }
    }
}
