package co.ticketing.usecase.payment;

import co.ticketing.model.payment.authorization.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReconcilePaymentAuthorizationUseCaseTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T20:00:00Z");

    private final PaymentAuthorizationRepository repository =
            mock(PaymentAuthorizationRepository.class);

    private final PaymentAuthorizationLookupGateway gateway =
            mock(PaymentAuthorizationLookupGateway.class);

    private final ReconcilePaymentAuthorizationUseCase useCase =
            new ReconcilePaymentAuthorizationUseCase(
                    repository, gateway,
                    Clock.fixed(NOW, ZoneOffset.UTC));

    private PaymentAuthorization pending() {
        return PaymentAuthorization.pending("order-1", NOW);
    }

    @Test
    void approvedProviderResultIsPersisted() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(pending()));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Approved("auth-1")));
        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute("order-1"))
                .assertNext(a -> {
                    assertThat(a.status()).isEqualTo(
                            PaymentAuthorization.Status.APPROVED);
                    assertThat(a.authorizationId()).isEqualTo("auth-1");
                    assertThat(a.version()).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void declinedProviderResultIsPersisted() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(pending()));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Declined(
                                "CARD_DECLINED")));
        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute("order-1"))
                .assertNext(a -> assertThat(a.status()).isEqualTo(
                        PaymentAuthorization.Status.DECLINED))
                .verifyComplete();
    }

    @Test
    void unknownResultRequiresReconciliation() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(pending()));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Unknown()));
        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute("order-1"))
                .assertNext(a -> assertThat(a.status()).isEqualTo(
                        PaymentAuthorization.Status.NEEDS_RECONCILIATION))
                .verifyComplete();
    }

    @Test
    void terminalAuthorizationSkipsProvider() {
        var approved = PaymentAuthorizationTransitions.approve(
                pending(), "auth-1", NOW);

        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(approved));

        StepVerifier.create(useCase.execute("order-1"))
                .expectNext(approved)
                .verifyComplete();

        verifyNoInteractions(gateway);
    }

    @Test
    void concurrentUpdateConflictIsNotIgnored() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(pending()));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Approved("auth-1")));
        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute("order-1"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void reconciliationStateCanBecomeApproved() {
        var current = PaymentAuthorizationTransitions.reconcile(
                pending(), NOW);

        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(current));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Approved("auth-1")));
        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute("order-1"))
                .assertNext(a -> {
                    assertThat(a.status()).isEqualTo(
                            PaymentAuthorization.Status.APPROVED);
                    assertThat(a.authorizationId()).isEqualTo("auth-1");
                    assertThat(a.version()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    void repeatedUnknownDoesNotWriteAgain() {
        var current = PaymentAuthorizationTransitions.reconcile(
                pending(), NOW);

        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(current));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.just(
                        new PaymentAuthorizationLookup.Unknown()));

        StepVerifier.create(useCase.execute("order-1"))
                .expectNext(current)
                .verifyComplete();

        verify(repository, never())
                .updateIfVersionMatches(any());
    }

    @Test
    void providerLookupFailureDoesNotModifyAuthorization() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.just(pending()));
        when(gateway.findByOrderId("order-1"))
                .thenReturn(Mono.error(
                        new IllegalStateException(
                                "Provider lookup unavailable")));

        StepVerifier.create(useCase.execute("order-1"))
                .expectError(IllegalStateException.class)
                .verify();

        verify(repository, never())
                .updateIfVersionMatches(any());
    }

    @Test
    void missingAuthorizationDoesNotQueryProvider() {
        when(repository.findByOrderId("order-1"))
                .thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute("order-1"))
                .expectError(IllegalStateException.class)
                .verify();

        verifyNoInteractions(gateway);
    }


    @Test
    void simultaneousReconciliationsCannotBothCommit() {
        var current = pending();

        var saved = new java.util.concurrent.atomic.AtomicReference<>(current);
        var bothStarted = new java.util.concurrent.CountDownLatch(2);

        when(repository.findByOrderId("order-1"))
                .thenAnswer(invocation -> Mono.just(saved.get()));

        when(gateway.findByOrderId("order-1"))
                .thenAnswer(invocation ->
                        Mono.fromCallable(() -> {
                            bothStarted.countDown();
                            if (!bothStarted.await(
                                    5, java.util.concurrent.TimeUnit.SECONDS)) {
                                throw new IllegalStateException(
                                        "Concurrent lookup timeout");
                            }
                            return new PaymentAuthorizationLookup.Approved(
                                    "auth-1");
                        }));

        when(repository.updateIfVersionMatches(any()))
                .thenAnswer(invocation -> Mono.fromSupplier(() -> {
                    PaymentAuthorization updated =
                            invocation.getArgument(0);
                    return saved.compareAndSet(current, updated);
                }));

        var first = useCase.execute("order-1")
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(result -> "approved")
                .onErrorReturn("conflict");

        var second = useCase.execute("order-1")
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(result -> "approved")
                .onErrorReturn("conflict");

        StepVerifier.create(
                reactor.core.publisher.Flux.merge(first, second)
                        .collectList())
                .assertNext(results -> {
                    assertThat(results).containsExactlyInAnyOrder(
                            "approved", "conflict");
                    assertThat(saved.get().status()).isEqualTo(
                            PaymentAuthorization.Status.APPROVED);
                })
                .verifyComplete();

        verify(repository, times(2)).updateIfVersionMatches(any());
    }

}
