package co.ticketing.usecase.complimentary;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.Quantity;
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
import static co.ticketing.usecase.UseCaseFixtures.EVENT_ID;
import static co.ticketing.usecase.UseCaseFixtures.event;
import static co.ticketing.usecase.UseCaseFixtures.pastEvent;
import static co.ticketing.usecase.UseCaseFixtures.sequentialIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IssueComplimentaryTicketsUseCaseTest {

    @Mock private EventRepository events;
    @Mock private OrderTransitionStore store;

    private IssueComplimentaryTicketsUseCase useCase;
    private final IssueComplimentaryCommand command = new IssueComplimentaryCommand(
            EVENT_ID, new CustomerId("vip-1"), new Quantity(2), new CustomerId("admin-1"));

    @BeforeEach
    void setUp() {
        useCase = new IssueComplimentaryTicketsUseCase(events, store, sequentialIds(), CLOCK);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(event()));
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.Committed(invocation.getArgument(0))));
    }

    @Test
    void issuesFinalComplimentaryTickets() {
        StepVerifier.create(useCase.execute(command))
                .assertNext(order -> {
                    assertThat(order.status()).isEqualTo(OrderStatus.COMPLIMENTARY);
                    assertThat(order.ticketIds()).hasSize(2);
                })
                .verifyComplete();
    }

    @Test
    void failsWhenNotEnoughTicketsAreAvailable() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.InsufficientInventory(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command)).verifyError(SoldOutException.class);
    }

    @Test
    void failsOnIdentifierCollision() {
        when(store.commit(any())).thenAnswer(invocation -> Mono.just(new CommitResult.StaleOrder(invocation.getArgument(0))));

        StepVerifier.create(useCase.execute(command)).verifyError(InvalidStateTransitionException.class);
    }

    @Test
    void failsForUnknownOrPastEvents() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(command)).verifyError(EventNotFoundException.class);

        when(events.findById(EVENT_ID)).thenReturn(Mono.just(pastEvent()));
        StepVerifier.create(useCase.execute(command)).verifyError(SalesClosedException.class);
    }
}
