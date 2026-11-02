package co.ticketing.usecase.order;

import co.ticketing.model.order.CommitResult;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.usecase.payment.AuthorizePaymentUseCase;

import co.ticketing.usecase.payment.ReconcilePaymentAuthorizationUseCase;
import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationTransitions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.ORDER_ID;
import static co.ticketing.usecase.UseCaseFixtures.pendingConfirmation;
import static co.ticketing.usecase.UseCaseFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProcessPaymentUseCaseTest {

    @Mock private OrderRepository orders;
    @Mock private OrderTransitionStore store;
    @Mock private AuthorizePaymentUseCase payments;

    private ProcessPaymentUseCase useCase;
    private final ProcessPaymentCommand command = new ProcessPaymentCommand(ORDER_ID, "tok_visa", NOW);

    @BeforeEach
    void setUp() {
        useCase = new ProcessPaymentUseCase(orders, store, payments, CLOCK);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(pendingConfirmation(2)));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));
    }

    @Test
    void approvedPaymentSellsTheTickets() {
        when(payments.execute(any())).thenReturn(Mono.just(new PaymentResult.Approved("auth-1")));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(PaymentOutcome.Sold.class);
                    assertThat(((PaymentOutcome.Sold) outcome).order().status()).isEqualTo(OrderStatus.SOLD);
                })
                .verifyComplete();
    }

    @Test
    void declinedPaymentReleasesTheTickets() {
        when(payments.execute(any())).thenReturn(Mono.just(new PaymentResult.Declined("CARD_DECLINED")));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(PaymentOutcome.Declined.class);
                    assertThat(((PaymentOutcome.Declined) outcome).order().status()).isEqualTo(OrderStatus.REJECTED);
                    assertThat(((PaymentOutcome.Declined) outcome).reason()).isEqualTo("CARD_DECLINED");
                })
                .verifyComplete();
    }

    @Test
    void approvedPaymentOnAReleasedOrderRequiresARefund() {
        when(orders.findById(ORDER_ID))
                .thenReturn(
                        Mono.just(pendingConfirmation(2)),
                        Mono.just(pendingConfirmation(2)
                                .reject(
                                        RejectionReason.PAYMENT_DECLINED,
                                        Order.WORKER_ACTOR,
                                        NOW)
                                .after()));
        when(payments.execute(any())).thenReturn(Mono.just(new PaymentResult.Approved("auth-1")));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> assertThat(outcome).isEqualTo(new PaymentOutcome.RefundRequired(ORDER_ID, "auth-1")))
                .verifyComplete();
    }

    @Test
    void declinedPaymentOnAReleasedOrderIsAlreadyProcessed() {
        when(payments.execute(any())).thenReturn(Mono.just(new PaymentResult.Declined("CARD_DECLINED")));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(PaymentOutcome.AlreadyProcessed.class))
                .verifyComplete();
    }

    @Test
    void ordersNotAwaitingPaymentAreNotCharged() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(reserved(2)));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> assertThat(outcome).isEqualTo(new PaymentOutcome.AlreadyProcessed(ORDER_ID)))
                .verifyComplete();
        verify(payments, never()).execute(any());
    }

    @Test
    void commitFailureDoesNotAuthorizePaymentTwice() {
        when(payments.execute(any()))
                .thenReturn(Mono.just(
                        new PaymentResult.Approved("auth-1")));

        when(store.commit(any()))
                .thenReturn(Mono.error(
                        new TransientFailureException(
                                "DynamoDB temporarily unavailable")));

        StepVerifier.create(useCase.execute(command))
                .expectError(TransientFailureException.class)
                .verify();

        org.mockito.Mockito.verify(
                payments, org.mockito.Mockito.times(1)
        ).execute(any());
    }


    @Test
    void recoveredApprovalCanSellOrder() {
        var reconciliation = org.mockito.Mockito.mock(
                ReconcilePaymentAuthorizationUseCase.class);

        useCase = new ProcessPaymentUseCase(
                orders, store, payments, CLOCK, reconciliation);

        when(payments.execute(any())).thenReturn(
                Mono.error(new AuthorizePaymentUseCase
                        .ReconciliationRequiredException(ORDER_ID.value())));

        var approved = PaymentAuthorizationTransitions.approve(
                PaymentAuthorization.pending(ORDER_ID.value(), NOW),
                "auth-recovered", NOW);

        when(reconciliation.execute(ORDER_ID.value()))
                .thenReturn(Mono.just(approved));

        StepVerifier.create(useCase.execute(command))
                .expectNextMatches(result ->
                        result instanceof PaymentOutcome.Sold)
                .verifyComplete();

        verify(reconciliation).execute(ORDER_ID.value());
        verify(store).commit(any());
        verify(payments, org.mockito.Mockito.times(1)).execute(any());
    }

    @Test
    void recoveredDeclineCanRejectOrder() {
        var reconciliation = org.mockito.Mockito.mock(
                ReconcilePaymentAuthorizationUseCase.class);

        useCase = new ProcessPaymentUseCase(
                orders, store, payments, CLOCK, reconciliation);

        when(payments.execute(any())).thenReturn(
                Mono.error(new AuthorizePaymentUseCase
                        .ReconciliationRequiredException(ORDER_ID.value())));

        var declined = PaymentAuthorizationTransitions.decline(
                PaymentAuthorization.pending(ORDER_ID.value(), NOW),
                "CARD_DECLINED", NOW);

        when(reconciliation.execute(ORDER_ID.value()))
                .thenReturn(Mono.just(declined));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result).isInstanceOf(
                            PaymentOutcome.Declined.class);
                    assertThat(((PaymentOutcome.Declined) result).reason())
                            .isEqualTo("CARD_DECLINED");
                })
                .verifyComplete();

        verify(reconciliation).execute(ORDER_ID.value());
        verify(store).commit(any());
    }

    @Test
    void unknownPaymentDoesNotSettleOrder() {
        var reconciliation = org.mockito.Mockito.mock(
                ReconcilePaymentAuthorizationUseCase.class);

        useCase = new ProcessPaymentUseCase(
                orders, store, payments, CLOCK, reconciliation);

        when(payments.execute(any())).thenReturn(
                Mono.error(new AuthorizePaymentUseCase
                        .ReconciliationRequiredException(ORDER_ID.value())));

        var uncertain = PaymentAuthorizationTransitions.reconcile(
                PaymentAuthorization.pending(ORDER_ID.value(), NOW),
                NOW);

        when(reconciliation.execute(ORDER_ID.value()))
                .thenReturn(Mono.just(uncertain));

        StepVerifier.create(useCase.execute(command))
                .expectErrorMatches(error ->
                        error instanceof IllegalStateException
                        && error.getMessage().contains("uncertain"))
                .verify();

        verify(store, never()).commit(any());
        verify(payments, org.mockito.Mockito.times(1)).execute(any());
    }


    @Test
    void alreadySoldOrderDoesNotRequireRefund() {
        when(payments.execute(any()))
                .thenReturn(Mono.just(
                        new PaymentResult.Approved("auth-1")));

        when(store.commit(any()))
                .thenAnswer(invocation ->
                        Mono.just(new CommitResult.StaleOrder(
                                invocation.getArgument(0))));

        when(orders.findById(ORDER_ID))
                .thenReturn(
                        Mono.just(pendingConfirmation(2)),
                        Mono.just(pendingConfirmation(2)
                                .markSold(NOW)
                                .after()));

        StepVerifier.create(useCase.execute(command))
                .expectNext(new PaymentOutcome.AlreadyProcessed(ORDER_ID))
                .verifyComplete();
    }

    @Test
    void unresolvedConflictCannotTriggerRefund() {
        when(payments.execute(any()))
                .thenReturn(Mono.just(
                        new PaymentResult.Approved("auth-1")));

        when(store.commit(any()))
                .thenAnswer(invocation ->
                        Mono.just(new CommitResult.StaleOrder(
                                invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command))
                .expectError(IllegalStateException.class)
                .verify();
    }

}
