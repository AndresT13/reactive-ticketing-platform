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
import co.ticketing.web.error.RequestValidator;
import co.ticketing.web.handler.AvailabilityCache;
import co.ticketing.web.handler.AvailabilityHandler;
import co.ticketing.web.handler.EventHandler;
import co.ticketing.web.handler.OrderHandler;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TicketingWebConfigTest {

    @Test
    void wiresTheApiComponents() {
        TicketingWebConfig config = new TicketingWebConfig();
        WebProperties properties = new WebProperties(null, null, null, null);
        RequestValidator validator = config.requestValidator(Validation.buildDefaultValidatorFactory().getValidator());
        AvailabilityCache cache = config.availabilityCache(mock(GetAvailabilityUseCase.class), properties);
        EventHandler events = config.eventHandler(mock(CreateEventUseCase.class), mock(GetEventUseCase.class),
                mock(ListEventsUseCase.class), mock(IssueComplimentaryTicketsUseCase.class), validator);
        OrderHandler orders = config.orderHandler(mock(PlaceOrderUseCase.class), mock(GetOrderUseCase.class),
                mock(ConfirmOrderUseCase.class), mock(CancelOrderUseCase.class), validator);
        AvailabilityHandler availability = config.availabilityHandler(cache, properties);

        assertThat(config.ticketingRoutes(events, orders, availability)).isNotNull();
        assertThat(config.correlationIdWebFilter()).isNotNull();
        assertThat(config.rateLimitWebFilter(properties)).isNotNull();
    }

    @Test
    void propertiesHaveSafeDefaults() {
        WebProperties defaults = new WebProperties(null, null, null, null);

        assertThat(defaults.availabilityCacheTtl()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.streamInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.streamMaxDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.rateLimit().capacity()).isEqualTo(20);
        WebProperties custom = new WebProperties(Duration.ofMillis(500), Duration.ofMillis(200), Duration.ofMinutes(5),
                new WebProperties.RateLimit(5, 1));
        assertThat(custom.rateLimit().refillPerSecond()).isEqualTo(1);
    }
}
