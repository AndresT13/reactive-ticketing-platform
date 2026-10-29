package co.ticketing.scheduler;

import co.ticketing.usecase.housekeeping.ReleaseExpiredReservationsUseCase;
import co.ticketing.usecase.housekeeping.RepublishStalledOrdersUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Asynchronous, time-driven entry point (RF-06). Every tick runs the compensation sweep and the outbox
 * re-dispatch. Ticks never overlap (concatMap) and are dropped while a previous sweep is still running.
 * Running it on several instances is safe: every release is a conditional, idempotent write.
 */
public final class OrderHousekeepingScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OrderHousekeepingScheduler.class);

    private final ReleaseExpiredReservationsUseCase releaseExpired;
    private final RepublishStalledOrdersUseCase republishStalled;
    private final Duration initialDelay;
    private final Duration interval;
    private final MeterRegistry meters;
    private volatile Disposable subscription;

    public OrderHousekeepingScheduler(ReleaseExpiredReservationsUseCase releaseExpired,
                                      RepublishStalledOrdersUseCase republishStalled,
                                      Duration initialDelay, Duration interval, MeterRegistry meters) {
        this.releaseExpired = releaseExpired;
        this.republishStalled = republishStalled;
        this.initialDelay = initialDelay;
        this.interval = interval;
        this.meters = meters;
    }

    @Override
    public void start() {
        subscription = Flux.interval(initialDelay, interval)
                .onBackpressureDrop()
                .concatMap(tick -> runOnce())
                .subscribe();
        log.info("Order housekeeping scheduled every {}", interval);
    }

    @Override
    public void stop() {
        Disposable current = subscription;
        if (current != null) {
            current.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        Disposable current = subscription;
        return current != null && !current.isDisposed();
    }

    Mono<HousekeepingReport> runOnce() {
        return Mono.zip(
                        releaseExpired.execute().onErrorResume(error -> fallback("release", error)),
                        republishStalled.execute().onErrorResume(error -> fallback("redispatch", error)))
                .map(counts -> new HousekeepingReport(counts.getT1(), counts.getT2()))
                .doOnNext(this::record);
    }

    private Mono<Long> fallback(String task, Throwable error) {
        meters.counter("ticketing.housekeeping.failures", "task", task).increment();
        log.warn("Housekeeping task {} failed; it will run again on the next tick", task, error);
        return Mono.just(0L);
    }

    private void record(HousekeepingReport report) {
        meters.counter("ticketing.reservations.released").increment(report.released());
        meters.counter("ticketing.orders.redispatched").increment(report.redispatched());
        if (report.released() + report.redispatched() > 0) {
            log.info("Housekeeping released {} orders and re-dispatched {}", report.released(), report.redispatched());
        }
    }
}
