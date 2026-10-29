package co.ticketing.web.config;

import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import co.ticketing.usecase.complimentary.IssueComplimentaryTicketsUseCase;
import co.ticketing.usecase.event.CreateEventUseCase;
import co.ticketing.usecase.event.GetEventUseCase;
import co.ticketing.usecase.event.ListEventsUseCase;
import co.ticketing.usecase.order.CancelOrderUseCase;
import co.ticketing.usecase.order.ConfirmOrderUseCase;
import co.ticketing.usecase.order.GetOrderUseCase;
import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.web.error.ProblemDetailsFilter;
import co.ticketing.web.error.RequestValidator;
import co.ticketing.web.filter.CorrelationIdWebFilter;
import co.ticketing.web.filter.RateLimitWebFilter;
import co.ticketing.web.filter.TokenBucketRateLimiter;
import co.ticketing.web.handler.AvailabilityCache;
import co.ticketing.web.handler.AvailabilityHandler;
import co.ticketing.web.handler.EventHandler;
import co.ticketing.web.handler.OrderHandler;
import jakarta.validation.Validator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

/** API role wiring. Disabled on worker-only instances ({@code ticketing.roles.api=false}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
@ConditionalOnProperty(name = "ticketing.roles.api", havingValue = "true", matchIfMissing = true)
public class TicketingWebConfig {

    @Bean
    RequestValidator requestValidator(Validator validator) {
        return new RequestValidator(validator);
    }

    @Bean
    AvailabilityCache availabilityCache(GetAvailabilityUseCase getAvailability, WebProperties properties) {
        return new AvailabilityCache(getAvailability, properties.availabilityCacheTtl());
    }

    @Bean
    EventHandler eventHandler(CreateEventUseCase createEvent, GetEventUseCase getEvent, ListEventsUseCase listEvents,
                              IssueComplimentaryTicketsUseCase issueComplimentary, RequestValidator validator) {
        return new EventHandler(createEvent, getEvent, listEvents, issueComplimentary, validator);
    }

    @Bean
    OrderHandler orderHandler(PlaceOrderUseCase placeOrder, GetOrderUseCase getOrder, ConfirmOrderUseCase confirmOrder,
                              CancelOrderUseCase cancelOrder, RequestValidator validator) {
        return new OrderHandler(placeOrder, getOrder, confirmOrder, cancelOrder, validator);
    }

    @Bean
    AvailabilityHandler availabilityHandler(AvailabilityCache cache, WebProperties properties) {
        return new AvailabilityHandler(cache, properties.streamInterval(), properties.streamMaxDuration());
    }

    @Bean
    RouterFunction<ServerResponse> ticketingRoutes(EventHandler events, OrderHandler orders, AvailabilityHandler availability) {
        return TicketingRoutes.routes(events, orders, availability, new ProblemDetailsFilter());
    }

    @Bean
    CorrelationIdWebFilter correlationIdWebFilter() {
        return new CorrelationIdWebFilter();
    }

    @Bean
    RateLimitWebFilter rateLimitWebFilter(WebProperties properties) {
        return new RateLimitWebFilter(new TokenBucketRateLimiter(properties.rateLimit().capacity(),
                properties.rateLimit().refillPerSecond(), System::nanoTime));
    }
}
