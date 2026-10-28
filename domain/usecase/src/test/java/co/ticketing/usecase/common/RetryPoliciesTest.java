package co.ticketing.usecase.common;

import co.ticketing.model.common.exception.TransientFailureException;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPoliciesTest {

    @Test
    void retriesTransientFailuresThreeTimesAndKeepsTheOriginalError() {
        AtomicInteger subscriptions = new AtomicInteger();
        Mono<String> alwaysFailing = Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.error(new TransientFailureException("throttled"));
        });

        StepVerifier.withVirtualTime(() -> alwaysFailing.transform(RetryPolicies.onTransientFailure()))
                .thenAwait(Duration.ofSeconds(5))
                .verifyError(TransientFailureException.class);
        assertThat(subscriptions).hasValue(4);
    }

    @Test
    void doesNotRetryBusinessOrUnknownErrors() {
        AtomicInteger subscriptions = new AtomicInteger();
        Mono<String> failing = Mono.defer(() -> {
            subscriptions.incrementAndGet();
            return Mono.error(new IllegalStateException("bug"));
        });

        StepVerifier.create(failing.transform(RetryPolicies.onTransientFailure())).verifyError(IllegalStateException.class);
        assertThat(subscriptions).hasValue(1);
    }

    @Test
    void ticketIdsAreGeneratedPerQuantity() {
        AtomicInteger counter = new AtomicInteger();
        assertThat(TicketIds.generate(() -> "t" + counter.incrementAndGet(), new co.ticketing.model.order.Quantity(3)))
                .extracting(co.ticketing.model.ticket.TicketId::value)
                .containsExactly("t1", "t2", "t3");
    }
}
