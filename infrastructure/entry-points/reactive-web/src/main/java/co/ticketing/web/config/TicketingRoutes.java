package co.ticketing.web.config;

import co.ticketing.web.error.ProblemDetailsFilter;
import co.ticketing.web.handler.AvailabilityHandler;
import co.ticketing.web.handler.EventHandler;
import co.ticketing.web.handler.OrderHandler;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

/** Functional route table (REST design rules: plural collections, verbs only on controller resources). */
public final class TicketingRoutes {

    private TicketingRoutes() {
    }

    public static RouterFunction<ServerResponse> routes(EventHandler events, OrderHandler orders,
                                                        AvailabilityHandler availability, ProblemDetailsFilter errors) {
        return RouterFunctions.route()
                .path("/api/v1", api -> api
                        .POST("/events", events::create)
                        .GET("/events", events::list)
                        .GET("/events/{eventId}/availability/stream", availability::stream)
                        .GET("/events/{eventId}/availability", availability::get)
                        .GET("/events/{eventId}", events::get)
                        .POST("/events/{eventId}/complimentary-tickets", events::issueComplimentary)
                        .POST("/orders", orders::place)
                        .GET("/orders/{orderId}", orders::get)
                        .POST("/orders/{orderId}/confirm", orders::confirm)
                        .POST("/orders/{orderId}/cancel", orders::cancel))
                .filter(errors)
                .build();
    }
}
