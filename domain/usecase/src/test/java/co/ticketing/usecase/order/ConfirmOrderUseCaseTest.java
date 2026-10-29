package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.ReservationExpiredException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.ZoneOffset;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.CUSTOMER;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.ORDER_ID;
import static co.ticketing.usecase.UseCaseFixtures.OTHER;
import static co.ticketing.usecase.UseCaseFixtures.POLICY;
import static co.ticketing.usecase.UseCaseFixtures.pendingConfirmation;
import static co.ticketing.usecase.UseCaseFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConfirmOrderUseCaseTest {

    @Mock private OrderRepository orders;
    @Mock private OrderTransitionStore store;
    @Mock private OrderCommandPublisher publisher;

    private ConfirmOrderUseCase useCase;
    private final ConfirmOrderCommand command = new ConfirmOrderCommand(ORDER_ID, CUSTOMER, "tok_visa");

    @BeforeEach
    void setUp() {
        useCase = new ConfirmOrderUseCase(orders, store, publisher, CLOCK, POLICY);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(reserved(2)));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));
        when(publisher.publish(any())).thenReturn(Mono.empty());
    }

    @Test
    void movesToPendingConfirmationAndPublishesPayment() {
        StepVerifier.create(useCase.execute(command))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION))
                .verifyComplete();
        verify(publisher).publish(argThat(published -> published instanceof ProcessPaymentCommand payment
                && payment.paymentToken().equals("tok_visa")));
    }

    @Test
    void repeatingTheConfirmationRepublishesThePayment() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(pendingConfirmation(2)));

        StepVerifier.create(useCase.execute(command))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION))
                .verifyComplete();
        verify(store, never()).commit(any());
        verify(publisher).publish(any());
    }

    @Test
    void soldOrdersAreReturnedAsIs() {
        Order sold = pendingConfirmation(2).markSold(NOW).after();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(sold));

        StepVerifier.create(useCase.execute(command)).expectNext(sold).verifyComplete();
        verify(publisher, never()).publish(any());
    }

    @Test
    void anotherCustomerGetsNotFound() {
        StepVerifier.create(useCase.execute(new ConfirmOrderCommand(ORDER_ID, OTHER, "tok")))
                .verifyError(OrderNotFoundException.class);
    }

    @Test
    void expiredReservationCannotBeConfirmed() {
        ConfirmOrderUseCase late = new ConfirmOrderUseCase(orders, store, publisher,
                Clock.fixed(NOW.plus(POLICY.reservationTtl()), ZoneOffset.UTC), POLICY);

        StepVerifier.create(late.execute(command)).verifyError(ReservationExpiredException.class);
        verify(store, never()).commit(any());
    }

    @Test
    void losingTheRaceAgainstTheSweeperIsAConflict() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command)).verifyError(InvalidStateTransitionException.class);
    }
}
