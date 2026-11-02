package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import co.ticketing.model.payment.gateways.PaymentGateway;
import co.ticketing.usecase.payment.AuthorizePaymentUseCase;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static co.ticketing.usecase.UseCaseFixtures.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DurablePaymentRedeliveryTest {

    @Test
    void redeliveryReusesAuthorizationAfterOrderCommitFailure() {

        OrderRepository orders = mock(OrderRepository.class);
        OrderTransitionStore store = mock(OrderTransitionStore.class);
        PaymentGateway gateway = mock(PaymentGateway.class);
        PaymentAuthorizationRepository authorizations =
                mock(PaymentAuthorizationRepository.class);

        AtomicReference<PaymentAuthorization> saved =
                new AtomicReference<>();

        when(orders.findById(ORDER_ID))
                .thenReturn(Mono.just(pendingConfirmation(2)));

        when(authorizations.findByOrderId(ORDER_ID.value()))
                .thenAnswer(invocation ->
                        Mono.defer(() -> Mono.justOrEmpty(saved.get())));

        when(authorizations.createIfAbsent(any()))
                .thenAnswer(invocation -> Mono.fromSupplier(() -> {
                    PaymentAuthorization pending =
                            invocation.getArgument(0);
                    return saved.compareAndSet(null, pending);
                }));

        when(authorizations.updateIfVersionMatches(any()))
                .thenAnswer(invocation -> Mono.fromSupplier(() -> {
                    PaymentAuthorization updated =
                            invocation.getArgument(0);
                    saved.set(updated);
                    return true;
                }));

        when(gateway.authorize(any()))
                .thenReturn(Mono.just(
                        new PaymentResult.Approved("auth-1")));

        when(store.commit(any()))
                .thenReturn(
                        Mono.error(new TransientFailureException(
                                "DynamoDB temporarily unavailable")))
                .thenAnswer(invocation ->
                        Mono.just(new CommitResult.Committed(
                                invocation.getArgument(0))));

        AuthorizePaymentUseCase authorize =
                new AuthorizePaymentUseCase(
                        authorizations, gateway, CLOCK);

        ProcessPaymentUseCase process =
                new ProcessPaymentUseCase(
                        orders, store, authorize, CLOCK);

        ProcessPaymentCommand command =
                new ProcessPaymentCommand(
                        ORDER_ID, "tok_visa", NOW);

        StepVerifier.create(process.execute(command))
                .expectError(TransientFailureException.class)
                .verify();

        StepVerifier.create(process.execute(command))
                .expectNextMatches(
                        result -> result instanceof PaymentOutcome.Sold)
                .verifyComplete();

        verify(gateway, times(1)).authorize(any());
        verify(store, times(2)).commit(any());
    }
}
