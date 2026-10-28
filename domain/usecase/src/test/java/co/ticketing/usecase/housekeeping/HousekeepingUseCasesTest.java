package co.ticketing.usecase.housekeeping;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;

import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.POLICY;
import static co.ticketing.usecase.UseCaseFixtures.pending;
import static co.ticketing.usecase.UseCaseFixtures.pendingConfirmation;
import static co.ticketing.usecase.UseCaseFixtures.reserved;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HousekeepingUseCasesTest {

    private static final Clock LATER = Clock.fixed(NOW.plus(Duration.ofHours(1)), ZoneOffset.UTC);

    @Mock private OrderRepository orders;
    @Mock private OrderTransitionStore store;
    @Mock private OrderCommandPublisher publisher;

    @Test
    void releasesExpiredReservationsAndTimedOutPayments() {
        Order expiredReservation = reserved(2);
        Order stuckPayment = pendingConfirmation(1);
        when(orders.findDue(any(), anyInt(), any(), anyInt())).thenReturn(Flux.empty());
        when(orders.findDue(eq(OrderStatus.RESERVED), eq(0), any(), anyInt())).thenReturn(Flux.just(expiredReservation));
        when(orders.findDue(eq(OrderStatus.PENDING_CONFIRMATION), eq(0), any(), anyInt())).thenReturn(Flux.just(stuckPayment));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));

        ReleaseExpiredReservationsUseCase useCase = new ReleaseExpiredReservationsUseCase(orders, store, LATER, POLICY, 100);

        StepVerifier.create(useCase.execute()).expectNext(2L).verifyComplete();
        verify(store).commit(argThat(transition -> transition.after().status() == OrderStatus.EXPIRED));
        verify(store).commit(argThat(transition -> transition.after().status() == OrderStatus.REJECTED));
    }

    @Test
    void skipsOrdersThatAreNotDueOrChangedConcurrently() {
        when(orders.findDue(any(), anyInt(), any(), anyInt())).thenReturn(Flux.empty());
        when(orders.findDue(eq(OrderStatus.RESERVED), eq(0), any(), anyInt())).thenReturn(Flux.just(reserved(1), pending(1)));
        ReleaseExpiredReservationsUseCase useCase = new ReleaseExpiredReservationsUseCase(orders, store,
                Clock.fixed(NOW, ZoneOffset.UTC), POLICY, 100);

        StepVerifier.create(useCase.execute()).expectNext(0L).verifyComplete();
        verify(store, never()).commit(any());
    }

    @Test
    void oneFailingOrderDoesNotAbortTheSweep() {
        when(orders.findDue(any(), anyInt(), any(), anyInt())).thenReturn(Flux.empty());
        when(orders.findDue(eq(OrderStatus.RESERVED), eq(0), any(), anyInt())).thenReturn(Flux.just(reserved(1)));
        when(orders.findDue(eq(OrderStatus.RESERVED), eq(1), any(), anyInt())).thenReturn(Flux.just(reserved(2)));
        when(store.commit(any())).thenAnswer(invocation -> {
            co.ticketing.model.order.OrderTransition transition = invocation.getArgument(0);
            return transition.after().quantity().value() == 1
                    ? Mono.error(new IllegalStateException("boom"))
                    : Mono.just(new CommitResult.StaleOrder(transition));
        });
        ReleaseExpiredReservationsUseCase useCase = new ReleaseExpiredReservationsUseCase(orders, store, LATER, POLICY, 100);

        StepVerifier.create(useCase.execute()).expectNext(0L).verifyComplete();
    }

    @Test
    void republishesStalledPendingOrdersAndPostponesTheirDeadline() {
        when(orders.findDue(any(), anyInt(), any(), anyInt())).thenReturn(Flux.empty());
        when(orders.findDue(eq(OrderStatus.PENDING), eq(0), any(), anyInt())).thenReturn(Flux.just(pending(1)));
        when(publisher.publish(any())).thenReturn(Mono.empty());
        when(orders.postponeDeadline(any(), any())).thenReturn(Mono.empty());
        RepublishStalledOrdersUseCase useCase = new RepublishStalledOrdersUseCase(orders, publisher, LATER, POLICY, 100);

        StepVerifier.create(useCase.execute()).expectNext(1L).verifyComplete();
        verify(publisher).publish(argThat(command -> command instanceof ReserveTicketsCommand));
        verify(orders).postponeDeadline(eq(pending(1).id()), eq(LATER.instant().plus(POLICY.pendingRedispatchAfter())));
    }

    @Test
    void republishFailuresAreSkippedUntilTheNextTick() {
        when(orders.findDue(any(), anyInt(), any(), anyInt())).thenReturn(Flux.empty());
        when(orders.findDue(eq(OrderStatus.PENDING), eq(0), any(), anyInt())).thenReturn(Flux.just(pending(1)));
        when(publisher.publish(any())).thenReturn(Mono.error(new TransientFailureException("sqs down")));
        RepublishStalledOrdersUseCase useCase = new RepublishStalledOrdersUseCase(orders, publisher, LATER, POLICY, 100);

        StepVerifier.create(useCase.execute()).expectNext(0L).verifyComplete();
        verify(orders, never()).postponeDeadline(any(), any());
    }
}
