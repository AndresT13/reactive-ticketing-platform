package co.ticketing.web.handler;

import co.ticketing.model.event.EventId;
import co.ticketing.model.idempotency.IdempotencyKey;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.usecase.order.CancelOrderUseCase;
import co.ticketing.usecase.order.ConfirmOrderCommand;
import co.ticketing.usecase.order.ConfirmOrderUseCase;
import co.ticketing.usecase.order.GetOrderUseCase;
import co.ticketing.usecase.order.PlaceOrderCommand;
import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.web.dto.ConfirmOrderRequest;
import co.ticketing.web.dto.OrderResponse;
import co.ticketing.web.dto.PlaceOrderRequest;
import co.ticketing.web.error.IdempotencyKeyRequiredException;
import co.ticketing.web.error.RequestValidationException;
import co.ticketing.web.error.RequestValidator;
import co.ticketing.web.security.AuthenticatedUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.net.URI;

/**
 * Purchase endpoints. Placing and confirming an order answer 202 Accepted (Asynchronous Request-Reply):
 * the heavy work continues in the workers and the client follows the Location / Retry-After headers.
 */
public final class OrderHandler {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String RETRY_AFTER_SECONDS = "1";

    private final PlaceOrderUseCase placeOrder;
    private final GetOrderUseCase getOrder;
    private final ConfirmOrderUseCase confirmOrder;
    private final CancelOrderUseCase cancelOrder;
    private final RequestValidator validator;

    public OrderHandler(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder, ConfirmOrderUseCase confirmOrder,
                        CancelOrderUseCase cancelOrder, RequestValidator validator) {
        this.placeOrder = placeOrder;
        this.getOrder = getOrder;
        this.confirmOrder = confirmOrder;
        this.cancelOrder = cancelOrder;
        this.validator = validator;
    }

    public Mono<ServerResponse> place(ServerRequest request) {
        String key = request.headers().firstHeader(IDEMPOTENCY_KEY_HEADER);
        if (key == null || key.isBlank()) {
            return Mono.error(new IdempotencyKeyRequiredException());
        }
        return Mono.zip(AuthenticatedUser.from(request), body(request, PlaceOrderRequest.class))
                .map(tuple -> new PlaceOrderCommand(tuple.getT1().customerId(), new EventId(tuple.getT2().eventId()),
                        new Quantity(tuple.getT2().quantity()), new IdempotencyKey(key)))
                .flatMap(placeOrder::execute)
                .flatMap(result -> accepted(result.order()));
    }

    public Mono<ServerResponse> get(ServerRequest request) {
        return AuthenticatedUser.from(request)
                .flatMap(user -> getOrder.execute(orderId(request), user.customerId(), user.admin()))
                .flatMap(order -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(OrderResponse.from(order)));
    }

    public Mono<ServerResponse> confirm(ServerRequest request) {
        return Mono.zip(AuthenticatedUser.from(request), body(request, ConfirmOrderRequest.class))
                .map(tuple -> new ConfirmOrderCommand(orderId(request), tuple.getT1().customerId(), tuple.getT2().paymentToken()))
                .flatMap(confirmOrder::execute)
                .flatMap(this::accepted);
    }

    public Mono<ServerResponse> cancel(ServerRequest request) {
        return AuthenticatedUser.from(request)
                .flatMap(user -> cancelOrder.execute(orderId(request), user.customerId()))
                .flatMap(order -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(OrderResponse.from(order)));
    }

    private Mono<ServerResponse> accepted(Order order) {
        return ServerResponse.accepted()
                .location(URI.create("/api/v1/orders/" + order.id().value()))
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(OrderResponse.from(order));
    }

    private <T> Mono<T> body(ServerRequest request, Class<T> type) {
        return request.bodyToMono(type)
                .switchIfEmpty(Mono.error(() -> new RequestValidationException("Request body is required")))
                .flatMap(validator::validate);
    }

    private static OrderId orderId(ServerRequest request) {
        return new OrderId(request.pathVariable("orderId"));
    }
}
