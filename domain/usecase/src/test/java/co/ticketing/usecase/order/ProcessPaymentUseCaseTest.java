package co.ticketing.usecase.order;

import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.gateways.PaymentGateway;
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
    @Mock private PaymentGateway payments;

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
        when(payments.authorize(any())).thenReturn(Mono.just(new PaymentResult.Approved("auth-1")));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(PaymentOutcome.Sold.class);
                    assertThat(((PaymentOutcome.Sold) outcome).order().status()).isEqualTo(OrderStatus.SOLD);
                })
                .verifyComplete();
    }

    @Test
    void declinedPaymentReleasesTheTickets() {
        when(payments.authorize(any())).thenReturn(Mono.just(new PaymentResult.Declined("CARD_DECLINED")));

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
        when(payments.authorize(any())).thenReturn(Mono.just(new PaymentResult.Approved("auth-1")));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command))
                .assertNext(outcome -> assertThat(outcome).isEqualTo(new PaymentOutcome.RefundRequired(ORDER_ID, "auth-1")))
                .verifyComplete();
    }

    @Test
    void declinedPaymentOnAReleasedOrderIsAlreadyProcessed() {
        when(payments.authorize(any())).thenReturn(Mono.just(new PaymentResult.Declined("CARD_DECLINED")));
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
        verify(payments, never()).authorize(any());
    }
}
