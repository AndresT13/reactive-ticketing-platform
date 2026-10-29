package co.ticketing.scheduler;

import co.ticketing.usecase.housekeeping.ReleaseExpiredReservationsUseCase;
import co.ticketing.usecase.housekeeping.RepublishStalledOrdersUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderHousekeepingSchedulerTest {

    private final ReleaseExpiredReservationsUseCase release = mock(ReleaseExpiredReservationsUseCase.class);
    private final RepublishStalledOrdersUseCase republish = mock(RepublishStalledOrdersUseCase.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OrderHousekeepingScheduler scheduler =
            new OrderHousekeepingScheduler(release, republish, Duration.ofHours(1), Duration.ofHours(1), meters);

    @Test
    void reportsWhatEachTickDid() {
        when(release.execute()).thenReturn(Mono.just(3L));
        when(republish.execute()).thenReturn(Mono.just(1L));

        StepVerifier.create(scheduler.runOnce()).expectNext(new HousekeepingReport(3, 1)).verifyComplete();
        assertThat(meters.counter("ticketing.reservations.released").count()).isEqualTo(3);
        assertThat(meters.counter("ticketing.orders.redispatched").count()).isEqualTo(1);
    }

    @Test
    void aFailingTaskDoesNotStopTheOther() {
        when(release.execute()).thenReturn(Mono.error(new IllegalStateException("query failed")));
        when(republish.execute()).thenReturn(Mono.just(0L));

        StepVerifier.create(scheduler.runOnce()).expectNext(new HousekeepingReport(0, 0)).verifyComplete();
        assertThat(meters.counter("ticketing.housekeeping.failures", "task", "release").count()).isEqualTo(1);
    }

    @Test
    void startsAndStops() {
        assertThat(scheduler.isRunning()).isFalse();
        scheduler.start();
        assertThat(scheduler.isRunning()).isTrue();
        scheduler.stop();
        assertThat(scheduler.isRunning()).isFalse();
    }
}
