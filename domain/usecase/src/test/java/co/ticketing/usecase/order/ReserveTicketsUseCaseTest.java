package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.RejectionReason;
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

import java.util.concurrent.atomic.AtomicInteger;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.ORDER_ID;
import static co.ticketing.usecase.UseCaseFixtures.POLICY;
import static co.ticketing.usecase.UseCaseFixtures.pending;
import static co.ticketing.usecase.UseCaseFixtures.reserved;
import static co.ticketing.usecase.UseCaseFixtures.sequentialIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReserveTicketsUseCaseTest {

    @Mock private OrderRepository orders;
    @Mock private OrderTransitionStore store;

    private ReserveTicketsUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ReserveTicketsUseCase(orders, store, sequentialIds(), CLOCK, POLICY);
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(pending(2)));
    }

    @Test
    void reservesWhenTheConditionalWriteSucceeds() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);
                    Order order = ((ReservationOutcome.Reserved) outcome).order();
                    assertThat(order.status()).isEqualTo(OrderStatus.RESERVED);
                    assertThat(order.ticketIds()).hasSize(2).doesNotHaveDuplicates();
                })
                .verifyComplete();
    }

    @Test
    void rejectsAsSoldOutWhenTheInventoryConditionFails() {
        when(store.commit(any())).thenAnswer(invocation -> {
            OrderTransition transition = invocation.getArgument(0);
            return Mono.just(transition.after().status() == OrderStatus.RESERVED
                    ? new CommitResult.InsufficientInventory(transition)
                    : new CommitResult.Committed(transition));
        });

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> {
                    assertThat(outcome).isInstanceOf(ReservationOutcome.Rejected.class);
                    assertThat(((ReservationOutcome.Rejected) outcome).reason()).isEqualTo(RejectionReason.SOLD_OUT);
                    assertThat(((ReservationOutcome.Rejected) outcome).order().status()).isEqualTo(OrderStatus.REJECTED);
                })
                .verifyComplete();
    }

    @Test
    void rejectionThatLosesTheRaceIsAlreadyProcessed() {
        when(store.commit(any())).thenAnswer(invocation -> {
            OrderTransition transition = invocation.getArgument(0);
            return Mono.just(transition.after().status() == OrderStatus.RESERVED
                    ? new CommitResult.InsufficientInventory(transition)
                    : new CommitResult.StaleOrder(transition));
        });

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(ReservationOutcome.AlreadyProcessed.class))
                .verifyComplete();
    }

    @Test
    void staleOrderMeansTheMessageWasADuplicate() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> assertThat(outcome).isEqualTo(new ReservationOutcome.AlreadyProcessed(ORDER_ID)))
                .verifyComplete();
    }

    @Test
    void ordersOutsidePendingAreAcknowledgedWithoutWriting() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.just(reserved(2)));

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(ReservationOutcome.AlreadyProcessed.class))
                .verifyComplete();
        verify(store, never()).commit(any());
    }

    @Test
    void missingOrdersAreAcknowledged() {
        when(orders.findById(ORDER_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(ReservationOutcome.AlreadyProcessed.class))
                .verifyComplete();
    }

    @Test
    void retriesTransientConflictsFromAFreshRead() {
        AtomicInteger commits = new AtomicInteger();
        when(store.commit(any())).thenAnswer(invocation -> commits.incrementAndGet() == 1
                ? Mono.error(new TransientFailureException("TransactionConflict"))
                : Mono.just(new CommitResult.Committed(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(ORDER_ID))
                .assertNext(outcome -> assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class))
                .verifyComplete();
        assertThat(commits).hasValue(2);
    }
}
