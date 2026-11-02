package co.ticketing.usecase.payment;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import co.ticketing.model.payment.gateways.PaymentGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizePaymentUseCaseTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T20:00:00Z");

    @Mock
    private PaymentAuthorizationRepository repository;

    @Mock
    private PaymentGateway gateway;

    private AuthorizePaymentUseCase useCase;

    private final PaymentRequest request = new PaymentRequest(
            new OrderId("order-123"),
            new CustomerId("customer-123"),
            new Quantity(1),
            "tok_visa"
    );

    @BeforeEach
    void setUp() {
        useCase = new AuthorizePaymentUseCase(
                repository,
                gateway,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private PaymentAuthorization approved() {
        return new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.APPROVED,
                "auth-123",
                null,
                NOW,
                1
        );
    }

    @Test
    void reusesApprovedAuthorizationWithoutCallingProvider() {
        when(repository.findByOrderId("order-123"))
                .thenReturn(Mono.just(approved()));

        StepVerifier.create(useCase.execute(request))
                .expectNext(new PaymentResult.Approved("auth-123"))
                .verifyComplete();

        verifyNoInteractions(gateway);
    }

    @Test
    void reusesDeclinedAuthorizationWithoutCallingProvider() {
        var declined = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.DECLINED,
                null,
                "CARD_DECLINED",
                NOW,
                1
        );

        when(repository.findByOrderId("order-123"))
                .thenReturn(Mono.just(declined));

        StepVerifier.create(useCase.execute(request))
                .expectNext(
                        new PaymentResult.Declined("CARD_DECLINED"))
                .verifyComplete();

        verifyNoInteractions(gateway);
    }

    @Test
    void pendingAuthorizationNeverCallsProviderAgain() {
        when(repository.findByOrderId("order-123"))
                .thenReturn(Mono.just(
                        PaymentAuthorization.pending("order-123", NOW)));

        StepVerifier.create(useCase.execute(request))
                .expectError(IllegalStateException.class)
                .verify();

        verifyNoInteractions(gateway);
    }

    @Test
    void createsAndPersistsNewApprovedAuthorization() {
        when(repository.findByOrderId("order-123"))
                .thenReturn(Mono.empty());

        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(true));

        when(gateway.authorize(request))
                .thenReturn(Mono.just(
                        new PaymentResult.Approved("auth-123")));

        when(repository.updateIfVersionMatches(any()))
                .thenReturn(Mono.just(true));

        StepVerifier.create(useCase.execute(request))
                .expectNext(new PaymentResult.Approved("auth-123"))
                .verifyComplete();

        verify(gateway, times(1)).authorize(request);

        verify(repository).updateIfVersionMatches(
                argThat(a ->
                        a.status() ==
                                PaymentAuthorization.Status.APPROVED
                        && a.version() == 1
                        && "auth-123".equals(a.authorizationId())
                )
        );
    }

    @Test
    void concurrentDuplicateReusesStoredResult() {
        when(repository.findByOrderId("order-123"))
                .thenReturn(
                        Mono.empty(),
                        Mono.just(approved())
                );

        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(false));

        StepVerifier.create(useCase.execute(request))
                .expectNext(new PaymentResult.Approved("auth-123"))
                .verifyComplete();

        verifyNoInteractions(gateway);
    }

    @Test
    void providerFailureDoesNotRetryAuthorization() {
        when(repository.findByOrderId("order-123"))
                .thenReturn(Mono.empty());

        when(repository.createIfAbsent(any()))
                .thenReturn(Mono.just(true));

        when(gateway.authorize(request))
                .thenReturn(Mono.error(
                        new TransientFailureException(
                                "Provider unavailable")));

        StepVerifier.create(useCase.execute(request))
                .expectError(TransientFailureException.class)
                .verify();

        verify(gateway, times(1)).authorize(request);
        verify(repository, never())
                .updateIfVersionMatches(any());
    }
}
