package co.ticketing.usecase.payment;

import co.ticketing.model.payment.compensation.PaymentCompensation;
import co.ticketing.model.payment.compensation.PaymentCompensationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RegisterPaymentCompensationUseCaseTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T12:00:00Z");

    private PaymentCompensationRepository repository;
    private RegisterPaymentCompensationUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentCompensationRepository.class);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        useCase = new RegisterPaymentCompensationUseCase(
                repository, clock);
    }

    @Test
    void createsPendingCompensation() {
        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute("order-1", "auth-1"))
                .expectNext(PaymentCompensation.pending(
                        "order-1", "auth-1", NOW))
                .verifyComplete();

        verify(repository).createIfAbsent(any());
        verify(repository, never()).findById(any());
    }

    @Test
    void duplicateReturnsExistingCompensation() {
        var existing = PaymentCompensation.pending(
                "order-1", "auth-1", NOW);

        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(false));

        when(repository.findById(eq("auth-1")))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.execute("order-1", "auth-1"))
                .expectNext(existing)
                .verifyComplete();
    }

    @Test
    void rejectsConflictingIdentity() {
        var existing = PaymentCompensation.pending(
                "another-order", "auth-1", NOW);

        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(false));

        when(repository.findById(eq("auth-1")))
                .thenReturn(Mono.just(existing));

        StepVerifier.create(useCase.execute("order-1", "auth-1"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void propagatesRepositoryFailure() {
        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.error(
                        new IllegalStateException("DynamoDB unavailable")));

        StepVerifier.create(useCase.execute("order-1", "auth-1"))
                .expectErrorMessage("DynamoDB unavailable")
                .verify();

        verify(repository, never()).findById(any());
    }
}
