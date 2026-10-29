package co.ticketing.web.handler;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Quantity;
import co.ticketing.usecase.complimentary.IssueComplimentaryCommand;
import co.ticketing.usecase.complimentary.IssueComplimentaryTicketsUseCase;
import co.ticketing.usecase.event.CreateEventUseCase;
import co.ticketing.usecase.event.GetEventUseCase;
import co.ticketing.usecase.event.ListEventsUseCase;
import co.ticketing.web.dto.ComplimentaryRequest;
import co.ticketing.web.dto.CreateEventRequest;
import co.ticketing.web.dto.EventPageResponse;
import co.ticketing.web.dto.EventResponse;
import co.ticketing.web.dto.OrderResponse;
import co.ticketing.web.dto.Requests;
import co.ticketing.web.error.RequestValidationException;
import co.ticketing.web.error.RequestValidator;
import co.ticketing.web.security.AuthenticatedUser;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

/** Event catalog endpoints (synchronous request/response, non-blocking execution). */
public final class EventHandler {

    private final CreateEventUseCase createEvent;
    private final GetEventUseCase getEvent;
    private final ListEventsUseCase listEvents;
    private final IssueComplimentaryTicketsUseCase issueComplimentary;
    private final RequestValidator validator;

    public EventHandler(CreateEventUseCase createEvent, GetEventUseCase getEvent, ListEventsUseCase listEvents,
                        IssueComplimentaryTicketsUseCase issueComplimentary, RequestValidator validator) {
        this.createEvent = createEvent;
        this.getEvent = getEvent;
        this.listEvents = listEvents;
        this.issueComplimentary = issueComplimentary;
        this.validator = validator;
    }

    public Mono<ServerResponse> create(ServerRequest request) {
        return body(request, CreateEventRequest.class)
                .map(CreateEventRequest::toCommand)
                .flatMap(createEvent::execute)
                .flatMap(event -> ServerResponse.created(URI.create("/api/v1/events/" + event.id().value()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return eventId(request)
                .flatMap(getEvent::execute)
                .flatMap(event -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(EventResponse.from(event)));
    }

    public Mono<ServerResponse> list(ServerRequest request) {
        return Mono.fromCallable(() -> request.queryParam("limit")
                        .map(limit -> Requests.positiveInt(limit, "limit"))
                        .orElse(ListEventsUseCase.DEFAULT_LIMIT))
                .flatMap(limit -> listEvents.execute(limit, request.queryParam("cursor").orElse(null)))
                .flatMap(page -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(EventPageResponse.from(page)));
    }

    public Mono<ServerResponse> issueComplimentary(ServerRequest request) {
        return Mono.zip(AuthenticatedUser.from(request), eventId(request), body(request, ComplimentaryRequest.class))
                .map(tuple -> new IssueComplimentaryCommand(tuple.getT2(), new CustomerId(tuple.getT3().recipientCustomerId()),
                        new Quantity(tuple.getT3().quantity()), tuple.getT1().customerId()))
                .flatMap(issueComplimentary::execute)
                .flatMap(order -> ServerResponse.created(URI.create("/api/v1/orders/" + order.id().value()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(OrderResponse.from(order)));
    }

    private <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .switchIfEmpty(Mono.error(() -> new RequestValidationException("Request body is required")))
                .flatMap(validator::validate);
    }

    private static Mono<EventId> eventId(ServerRequest request) {
        return Mono.fromCallable(() -> new EventId(request.pathVariable("eventId")));
    }
}
