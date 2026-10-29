package co.ticketing.web.error;

import co.ticketing.web.filter.CorrelationIdWebFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.HandlerFilterFunction;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/** Translates any error raised by a handler into an {@code application/problem+json} response. No stack traces leak. */
public final class ProblemDetailsFilter implements HandlerFilterFunction<ServerResponse, ServerResponse> {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsFilter.class);

    @Override
    public Mono<ServerResponse> filter(ServerRequest request, HandlerFunction<ServerResponse> next) {
        return Mono.defer(() -> next.handle(request))
                .onErrorResume(error -> respond(request, error));
    }

    Mono<ServerResponse> respond(ServerRequest request, Throwable error) {
        ApiError apiError = ErrorMapping.of(error);
        String correlationId = request.attribute(CorrelationIdWebFilter.ATTRIBUTE).map(Object::toString).orElse(null);
        if (apiError.status() >= 500) {
            log.error("Request {} {} failed [correlationId={}]", request.method(), request.path(), correlationId, error);
        } else {
            log.debug("Request {} {} rejected with {} [correlationId={}]", request.method(), request.path(), apiError.code(), correlationId);
        }
        ProblemResponse body = new ProblemResponse("about:blank", apiError.title(), apiError.status(), apiError.detail(),
                request.path(), apiError.code(), correlationId);
        ServerResponse.BodyBuilder response = ServerResponse.status(apiError.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (apiError.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, Long.toString(apiError.retryAfter().toSeconds()));
        }
        return response.bodyValue(body);
    }
}
