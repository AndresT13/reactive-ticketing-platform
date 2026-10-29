package co.ticketing.web.handler;

import co.ticketing.model.common.Page;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import co.ticketing.usecase.complimentary.IssueComplimentaryCommand;
import co.ticketing.usecase.complimentary.IssueComplimentaryTicketsUseCase;
import co.ticketing.usecase.event.CreateEventCommand;
import co.ticketing.usecase.event.CreateEventUseCase;
import co.ticketing.usecase.event.GetEventUseCase;
import co.ticketing.usecase.event.ListEventsUseCase;
import co.ticketing.usecase.order.CancelOrderUseCase;
import co.ticketing.usecase.order.ConfirmOrderUseCase;
import co.ticketing.usecase.order.GetOrderUseCase;
import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.web.config.TicketingRoutes;
import co.ticketing.web.dto.AvailabilityResponse;
import co.ticketing.web.error.ProblemDetailsFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static co.ticketing.web.WebTestSupport.EVENT_ID;
import static co.ticketing.web.WebTestSupport.NOW;
import static co.ticketing.web.WebTestSupport.SCOPES_HEADER;
import static co.ticketing.web.WebTestSupport.USER_HEADER;
import static co.ticketing.web.WebTestSupport.event;
import static co.ticketing.web.WebTestSupport.testAuthentication;
import static co.ticketing.web.WebTestSupport.validator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventAndAvailabilityHandlerTest {

    private static final String ADMIN_SCOPES = "SCOPE_ticketing:admin,SCOPE_ticketing:purchase";

    private final CreateEventUseCase createEvent = mock(CreateEventUseCase.class);
    private final GetEventUseCase getEvent = mock(GetEventUseCase.class);
    private final ListEventsUseCase listEvents = mock(ListEventsUseCase.class);
    private final IssueComplimentaryTicketsUseCase issueComplimentary = mock(IssueComplimentaryTicketsUseCase.class);
    private final GetAvailabilityUseCase getAvailability = mock(GetAvailabilityUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        EventHandler events = new EventHandler(createEvent, getEvent, listEvents, issueComplimentary, validator());
        OrderHandler orders = new OrderHandler(mock(PlaceOrderUseCase.class), mock(GetOrderUseCase.class),
                mock(ConfirmOrderUseCase.class), mock(CancelOrderUseCase.class), validator());
        AvailabilityHandler availability = new AvailabilityHandler(new AvailabilityCache(getAvailability, Duration.ofMillis(5)),
                Duration.ofMillis(10), Duration.ofSeconds(2));
        client = WebTestClient.bindToRouterFunction(TicketingRoutes.routes(events, orders, availability, new ProblemDetailsFilter()))
                .webFilter(testAuthentication())
                .build();
    }

    @Test
    void createsAnEvent() {
        when(createEvent.execute(any())).thenReturn(Mono.just(event()));

        client.post().uri("/api/v1/events").header(USER_HEADER, "admin-1").header(SCOPES_HEADER, ADMIN_SCOPES)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Rock Fest", "venue", Map.of("name", "Arena", "city", "Bogotá"),
                        "eventDate", "2026-12-12T20:00:00Z", "totalCapacity", 100))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/events/evt-1")
                .expectBody().jsonPath("$.venue.city").isEqualTo("Bogotá");

        ArgumentCaptor<CreateEventCommand> command = ArgumentCaptor.forClass(CreateEventCommand.class);
        verify(createEvent).execute(command.capture());
        assertThat(command.getValue().totalCapacity()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidEventPayloads() {
        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Rock", "venue", Map.of("name", "Arena", "city", "Bogotá"),
                        "eventDate", "tomorrow", "totalCapacity", 10))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(detail -> assertThat(detail.toString()).contains("eventDate"));

        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "", "eventDate", "2026-12-12T20:00:00Z", "totalCapacity", 0))
                .exchange()
                .expectStatus().isBadRequest();

        client.post().uri("/api/v1/events").contentType(MediaType.APPLICATION_JSON).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getsAndListsEvents() {
        when(getEvent.execute(EVENT_ID)).thenReturn(Mono.just(event()));
        when(listEvents.execute(anyInt(), any())).thenReturn(Mono.just(new Page<>(List.of(event()), "next")));

        client.get().uri("/api/v1/events/evt-1").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.name").isEqualTo("Rock Fest");
        client.get().uri("/api/v1/events?limit=5&cursor=abc").exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.nextCursor").isEqualTo("next");
        client.get().uri("/api/v1/events").exchange().expectStatus().isOk();
        client.get().uri("/api/v1/events?limit=abc").exchange().expectStatus().isBadRequest();

        verify(listEvents).execute(5, "abc");
        verify(listEvents).execute(eq(ListEventsUseCase.DEFAULT_LIMIT), isNull());
    }

    @Test
    void unknownEventIsNotFound() {
        when(getEvent.execute(any())).thenReturn(Mono.error(new EventNotFoundException("evt-9")));

        client.get().uri("/api/v1/events/evt-9").exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("EVENT_NOT_FOUND");
        client.get().uri("/api/v1/events/bad id").exchange().expectStatus().isBadRequest();
    }

    @Test
    void issuesComplimentaryTicketsOnBehalfOfTheAdmin() {
        Order complimentary = Order.issueComplimentary(new OrderId("cmp-1"), EVENT_ID, new co.ticketing.model.common.CustomerId("vip-1"),
                new Quantity(1), List.of(new TicketId("t-1")), new co.ticketing.model.common.CustomerId("admin-1"), NOW).after();
        when(issueComplimentary.execute(any())).thenReturn(Mono.just(complimentary));

        client.post().uri("/api/v1/events/evt-1/complimentary-tickets").header(USER_HEADER, "admin-1").header(SCOPES_HEADER, ADMIN_SCOPES)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("recipientCustomerId", "vip-1", "quantity", 1))
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals("Location", "/api/v1/orders/cmp-1")
                .expectBody().jsonPath("$.status").isEqualTo("COMPLIMENTARY");

        ArgumentCaptor<IssueComplimentaryCommand> command = ArgumentCaptor.forClass(IssueComplimentaryCommand.class);
        verify(issueComplimentary).execute(command.capture());
        assertThat(command.getValue().issuedBy().value()).isEqualTo("admin-1");
    }

    @Test
    void returnsAvailabilityWithShortCaching() {
        when(getAvailability.execute(EVENT_ID)).thenReturn(Mono.just(new Inventory(EVENT_ID, 10, 6, 2, 1, 1, 0, 4)));

        client.get().uri("/api/v1/events/evt-1/availability").exchange()
                .expectStatus().isOk()
                .expectHeader().cacheControl(org.springframework.http.CacheControl.maxAge(Duration.ofSeconds(1)))
                .expectBody()
                .jsonPath("$.available").isEqualTo(6)
                .jsonPath("$.held").isEqualTo(3);
    }

    @Test
    void streamsAvailabilityAsServerSentEvents() {
        when(getAvailability.execute(EVENT_ID)).thenReturn(Mono.just(Inventory.initial(EVENT_ID, 10)));

        Flux<AvailabilityResponse> stream = client.get().uri("/api/v1/events/evt-1/availability/stream")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isOk()
                .returnResult(AvailabilityResponse.class)
                .getResponseBody();

        StepVerifier.create(stream.take(1))
                .assertNext(update -> assertThat(update.available()).isEqualTo(10))
                .verifyComplete();
    }

    @Test
    void streamOfUnknownEventIsNotFound() {
        when(getAvailability.execute(any())).thenReturn(Mono.error(new EventNotFoundException("evt-9")));

        client.get().uri("/api/v1/events/evt-9/availability/stream").exchange().expectStatus().isNotFound();
    }

    @Test
    void availabilityCacheSharesOneReadAndForgetsFailures() {
        when(getAvailability.execute(EVENT_ID))
                .thenReturn(Mono.error(new IllegalStateException("boom")))
                .thenReturn(Mono.just(Inventory.initial(EVENT_ID, 10)));
        AvailabilityCache cache = new AvailabilityCache(getAvailability, Duration.ofMinutes(1));

        StepVerifier.create(cache.get(EVENT_ID)).verifyError(IllegalStateException.class);
        StepVerifier.create(cache.get(EVENT_ID)).expectNextCount(1).verifyComplete();
        StepVerifier.create(cache.get(EVENT_ID)).expectNextCount(1).verifyComplete();
        verify(getAvailability, times(2)).execute(EVENT_ID);
    }

    @Test
    void sseUpdatesOnlyWhenNumbersChange() {
        when(getAvailability.execute(EVENT_ID)).thenReturn(Mono.just(Inventory.initial(EVENT_ID, 10)));
        AvailabilityHandler handler = new AvailabilityHandler(new AvailabilityCache(getAvailability, Duration.ofMillis(1)),
                Duration.ofMillis(5), Duration.ofMillis(100));

        StepVerifier.create(handler.updates(new EventId("evt-1")))
                .assertNext(event -> assertThat(event.event()).isEqualTo("availability"))
                .verifyComplete();
    }
}
