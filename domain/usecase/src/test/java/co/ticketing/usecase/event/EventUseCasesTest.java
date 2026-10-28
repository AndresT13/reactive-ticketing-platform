package co.ticketing.usecase.event;

import co.ticketing.model.common.Page;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.EVENT_ID;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.event;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventUseCasesTest {

    @Mock private EventRepository events;
    @Mock private InventoryRepository inventories;

    @Test
    void createsEventWithFullyAvailableInventory() {
        when(events.create(any(), any())).thenReturn(Mono.empty());
        CreateEventUseCase useCase = new CreateEventUseCase(events, () -> "evt-new", CLOCK);

        StepVerifier.create(useCase.execute(new CreateEventCommand("Rock", "Arena", "Bogotá", NOW.plusSeconds(3600), 50)))
                .assertNext(event -> assertThat(event.id().value()).isEqualTo("evt-new"))
                .verifyComplete();

        ArgumentCaptor<Inventory> inventory = ArgumentCaptor.forClass(Inventory.class);
        verify(events).create(any(Event.class), inventory.capture());
        assertThat(inventory.getValue().available()).isEqualTo(50);
    }

    @Test
    void invalidEventsAreRejectedBeforePersisting() {
        CreateEventUseCase useCase = new CreateEventUseCase(events, () -> "evt-new", CLOCK);

        StepVerifier.create(useCase.execute(new CreateEventCommand("Rock", "Arena", "Bogotá", NOW.minusSeconds(1), 50)))
                .verifyError(DomainValidationException.class);
        verify(events, never()).create(any(), any());
    }

    @Test
    void getsAnEventOrFails() {
        GetEventUseCase useCase = new GetEventUseCase(events);
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(event()));
        StepVerifier.create(useCase.execute(EVENT_ID)).expectNextCount(1).verifyComplete();

        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(EVENT_ID)).verifyError(EventNotFoundException.class);
    }

    @Test
    void listClampsTheLimit() {
        ListEventsUseCase useCase = new ListEventsUseCase(events);
        when(events.findAll(anyInt(), any())).thenReturn(Mono.just(new Page<>(List.of(event()), null)));

        StepVerifier.create(useCase.execute(0, null)).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(1000, "cursor")).expectNextCount(1).verifyComplete();
        StepVerifier.create(useCase.execute(5, null)).expectNextCount(1).verifyComplete();

        verify(events).findAll(eq(ListEventsUseCase.DEFAULT_LIMIT), any());
        verify(events).findAll(ListEventsUseCase.MAX_LIMIT, "cursor");
        verify(events).findAll(eq(5), any());
    }

    @Test
    void availabilityComesFromTheInventory() {
        GetAvailabilityUseCase useCase = new GetAvailabilityUseCase(inventories);
        when(inventories.findByEventId(EVENT_ID)).thenReturn(Mono.just(Inventory.initial(EVENT_ID, 10)));
        StepVerifier.create(useCase.execute(EVENT_ID))
                .assertNext(inventory -> assertThat(inventory.available()).isEqualTo(10))
                .verifyComplete();

        when(inventories.findByEventId(EVENT_ID)).thenReturn(Mono.empty());
        StepVerifier.create(useCase.execute(EVENT_ID)).verifyError(EventNotFoundException.class);
    }
}
