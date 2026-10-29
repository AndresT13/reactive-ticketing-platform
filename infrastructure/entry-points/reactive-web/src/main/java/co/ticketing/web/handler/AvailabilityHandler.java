package co.ticketing.web.handler;

import co.ticketing.model.event.EventId;
import co.ticketing.web.dto.AvailabilityResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** Real-time availability (RF-07): a synchronous snapshot and a Server-Sent Events stream. */
public final class AvailabilityHandler {

    private final AvailabilityCache cache;
    private final Duration streamInterval;
    private final Duration streamMaxDuration;

    public AvailabilityHandler(AvailabilityCache cache, Duration streamInterval, Duration streamMaxDuration) {
        this.cache = cache;
        this.streamInterval = streamInterval;
        this.streamMaxDuration = streamMaxDuration;
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return eventId(request)
                .flatMap(cache::get)
                .flatMap(inventory -> ServerResponse.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofSeconds(1)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(AvailabilityResponse.from(inventory)));
    }

    /** Validates the event first (404 instead of an empty stream), then emits only when the numbers change. */
    public Mono<ServerResponse> stream(ServerRequest request) {
        return eventId(request).flatMap(eventId -> cache.get(eventId)
                .flatMap(first -> ServerResponse.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .body(BodyInserters.fromServerSentEvents(updates(eventId)))));
    }

    Flux<ServerSentEvent<AvailabilityResponse>> updates(EventId eventId) {
        return Flux.interval(Duration.ZERO, streamInterval)
                .onBackpressureDrop()
                .concatMap(tick -> cache.get(eventId).onErrorResume(error -> Mono.empty()))
                .distinctUntilChanged()
                .map(inventory -> ServerSentEvent.builder(AvailabilityResponse.from(inventory))
                        .event("availability")
                        .id(Long.toString(inventory.version()))
                        .build())
                .take(streamMaxDuration);
    }

    private static Mono<EventId> eventId(ServerRequest request) {
        return Mono.fromCallable(() -> new EventId(request.pathVariable("eventId")));
    }
}
