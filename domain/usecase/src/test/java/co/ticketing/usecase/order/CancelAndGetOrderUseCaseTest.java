package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
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

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.CUSTOMER;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.ORDER_ID;
import static co.ticketing.usecase.UseCaseFixtures.OTHER;
import static co.ticketing.usecase.UseCaseFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CancelAndGetOrderUseCaseTest {

    @Mock private OrderRepository orders;
    @Mock private OrderTransitionStore store;

    private CancelOrderUseCase cancel;
    private GetOrderUseCase get;

    @BeforeEach
    void setUp() {
        cancel = new CancelOrderUseCase(orders, store, CLOCK);
        get = new GetOrderUseCase(orders);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(reserved(1)));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));
    }

    @Test
    void ownerCancelsReservation() {
        StepVerifier.create(cancel.execute(ORDER_ID, CUSTOMER))
                .assertNext(order -> assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED))
                .verifyComplete();
    }

    @Test
    void cancellingTwiceIsIdempotent() {
        Order cancelled = reserved(1).cancel(CUSTOMER, NOW).after();
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(cancelled));

        StepVerifier.create(cancel.execute(ORDER_ID, CUSTOMER)).expectNext(cancelled).verifyComplete();
        verify(store, never()).commit(any());
    }

    @Test
    void cancellationThatLosesTheRaceIsAConflict() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(cancel.execute(ORDER_ID, CUSTOMER)).verifyError(InvalidStateTransitionException.class);
    }

    @Test
    void otherCustomersCannotCancel() {
        StepVerifier.create(cancel.execute(ORDER_ID, OTHER)).verifyError(OrderNotFoundException.class);
    }

    @Test
    void ownerAndAdministratorCanReadTheOrder() {
        StepVerifier.create(get.execute(ORDER_ID, CUSTOMER, false)).expectNextCount(1).verifyComplete();
        StepVerifier.create(get.execute(ORDER_ID, OTHER, true)).expectNextCount(1).verifyComplete();
    }

    @Test
    void otherCustomersCannotReadTheOrder() {
        StepVerifier.create(get.execute(ORDER_ID, OTHER, false)).verifyError(OrderNotFoundException.class);
    }
}
