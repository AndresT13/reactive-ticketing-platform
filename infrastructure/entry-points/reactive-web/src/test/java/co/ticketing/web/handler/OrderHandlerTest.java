package co.ticketing.web.handler;

import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import co.ticketing.usecase.complimentary.IssueComplimentaryTicketsUseCase;
import co.ticketing.usecase.event.CreateEventUseCase;
import co.ticketing.usecase.event.GetEventUseCase;
import co.ticketing.usecase.event.ListEventsUseCase;
import co.ticketing.usecase.order.CancelOrderUseCase;
import co.ticketing.usecase.order.ConfirmOrderUseCase;
import co.ticketing.usecase.order.GetOrderUseCase;
import co.ticketing.usecase.order.PlaceOrderCommand;
import co.ticketing.usecase.order.PlaceOrderResult;
import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.web.config.TicketingRoutes;
import co.ticketing.web.error.ProblemDetailsFilter;
import co.ticketing.web.filter.CorrelationIdWebFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

import static co.ticketing.web.WebTestSupport.CUSTOMER;
import static co.ticketing.web.WebTestSupport.NOW;
import static co.ticketing.web.WebTestSupport.USER_HEADER;
import static co.ticketing.web.WebTestSupport.pendingOrder;
import static co.ticketing.web.WebTestSupport.reservedOrder;
import static co.ticketing.web.WebTestSupport.testAuthentication;
import static co.ticketing.web.WebTestSupport.validator;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderHandlerTest {

    private final PlaceOrderUseCase placeOrder = mock(PlaceOrderUseCase.class);
    private final GetOrderUseCase getOrder = mock(GetOrderUseCase.class);
    private final ConfirmOrderUseCase confirmOrder = mock(ConfirmOrderUseCase.class);
    private final CancelOrderUseCase cancelOrder = mock(CancelOrderUseCase.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        EventHandler events = new EventHandler(mock(CreateEventUseCase.class), mock(GetEventUseCase.class),
                mock(ListEventsUseCase.class), mock(IssueComplimentaryTicketsUseCase.class), validator());
        OrderHandler orders = new OrderHandler(placeOrder, getOrder, confirmOrder, cancelOrder, validator());
        AvailabilityHandler availability = new AvailabilityHandler(
                new AvailabilityCache(mock(GetAvailabilityUseCase.class), Duration.ofSeconds(1)), Duration.ofSeconds(1), Duration.ofSeconds(1));
        client = WebTestClient.bindToRouterFunction(TicketingRoutes.routes(events, orders, availability, new ProblemDetailsFilter()))
                .webFilter(new CorrelationIdWebFilter(), testAuthentication())
                .build();
    }

    private WebTestClient.RequestHeadersSpec<?> place(Object body, String idempotencyKey) {
        WebTestClient.RequestBodySpec spec = client.post().uri("/api/v1/orders").header(USER_HEADER, CUSTOMER.value())
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            spec = spec.header(OrderHandler.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return spec.bodyValue(body);
    }

    @Test
    void placingAnOrderAnswersAcceptedWithLocationAndRetryAfter() {
        when(placeOrder.execute(any())).thenReturn(Mono.just(PlaceOrderResult.accepted(pendingOrder(), true)));

        place(Map.of("eventId", "evt-1", "quantity", 2), "key-00000001").exchange()
                .expectStatus().isAccepted()
                .expectHeader().valueEquals("Location", "/api/v1/orders/ord-1")
                .expectHeader().valueEquals("Retry-After", "1")
                .expectBody()
                .jsonPath("$.status").isEqualTo("PENDING")
                .jsonPath("$.ticketStatus").isEqualTo("AVAILABLE")
                .jsonPath("$._links.self.href").isEqualTo("/api/v1/orders/ord-1");

        ArgumentCaptor<PlaceOrderCommand> command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(placeOrder).execute(command.capture());
        assertThat(command.getValue().customerId()).isEqualTo(CUSTOMER);
        assertThat(command.getValue().idempotencyKey().value()).isEqualTo("key-00000001");
    }

    @Test
    void idempotencyKeyIsMandatory() {
        place(Map.of("eventId", "evt-1", "quantity", 2), null).exchange()
                .expectStatus().isEqualTo(428)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("IDEMPOTENCY_KEY_REQUIRED")
                .jsonPath("$.correlationId").isNotEmpty();
    }

    @Test
    void invalidBodiesAreRejected() {
        place(Map.of("eventId", "evt-1", "quantity", 0), "key-00000001").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");

        client.post().uri("/api/v1/orders").header(USER_HEADER, "customer-1")
                .header(OrderHandler.IDEMPOTENCY_KEY_HEADER, "key-00000001")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{not json").exchange()
                .expectStatus().isBadRequest();

        place(Map.of("eventId", "evt-1", "quantity", 1), "bad key").exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void anonymousCallersAreUnauthorized() {
        client.get().uri("/api/v1/orders/ord-1").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
    }

    @Test
    void soldOutIsAConflict() {
        when(placeOrder.execute(any())).thenReturn(Mono.error(new SoldOutException("evt-1", 2)));

        place(Map.of("eventId", "evt-1", "quantity", 2), "key-00000001").exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SOLD_OUT")
                .jsonPath("$.title").isEqualTo("Sold out")
                .jsonPath("$.instance").isEqualTo("/api/v1/orders");
    }

    @Test
    void transientFailuresAskTheClientToRetry() {
        when(placeOrder.execute(any())).thenReturn(Mono.error(new TransientFailureException("throttled")));

        place(Map.of("eventId", "evt-1", "quantity", 2), "key-00000001").exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().valueEquals("Retry-After", "2");
    }

    @Test
    void returnsTheOrderWithItsNextActions() {
        when(getOrder.execute(any(), eq(CUSTOMER), eq(false))).thenReturn(Mono.just(reservedOrder()));

        client.get().uri("/api/v1/orders/ord-1").header(USER_HEADER, CUSTOMER.value()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RESERVED")
                .jsonPath("$.ticketIds.length()").isEqualTo(2)
                .jsonPath("$._links.confirm.method").isEqualTo("POST")
                .jsonPath("$._links.cancel.href").isEqualTo("/api/v1/orders/ord-1/cancel");
    }

    @Test
    void unknownOrdersAreNotFound() {
        when(getOrder.execute(any(), any(), eq(false))).thenReturn(Mono.error(new OrderNotFoundException("ord-9")));

        client.get().uri("/api/v1/orders/ord-9").header(USER_HEADER, CUSTOMER.value()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    void confirmingAnswersAccepted() {
        Order confirming = reservedOrder().requestConfirmation(CUSTOMER, NOW, OrderPolicy.defaults()).after();
        when(confirmOrder.execute(any())).thenReturn(Mono.just(confirming));

        client.post().uri("/api/v1/orders/ord-1/confirm").header(USER_HEADER, CUSTOMER.value())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("paymentToken", "tok_visa")).exchange()
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("PENDING_CONFIRMATION");
    }

    @Test
    void cancellingAnswersOk() {
        Order cancelled = reservedOrder().cancel(CUSTOMER, NOW).after();
        when(cancelOrder.execute(any(), eq(CUSTOMER))).thenReturn(Mono.just(cancelled));

        client.post().uri("/api/v1/orders/ord-1/cancel").header(USER_HEADER, CUSTOMER.value()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED")
                .jsonPath("$.ticketStatus").isEqualTo("AVAILABLE");
    }

    @Test
    void echoesAValidCorrelationId() {
        when(getOrder.execute(any(), any(), eq(false))).thenReturn(Mono.just(reservedOrder()));

        client.get().uri("/api/v1/orders/ord-1").header(USER_HEADER, CUSTOMER.value())
                .header(CorrelationIdWebFilter.HEADER, "abc-123").exchange()
                .expectHeader().valueEquals(CorrelationIdWebFilter.HEADER, "abc-123");
    }
}
