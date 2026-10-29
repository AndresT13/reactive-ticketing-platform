package co.ticketing.web;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.Venue;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;
import co.ticketing.web.error.RequestValidator;
import jakarta.validation.Validation;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Instant;
import java.util.List;

public final class WebTestSupport {

    public static final String USER_HEADER = "X-Test-User";
    public static final String SCOPES_HEADER = "X-Test-Scopes";
    public static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");
    public static final EventId EVENT_ID = new EventId("evt-1");
    public static final CustomerId CUSTOMER = new CustomerId("customer-1");

    private WebTestSupport() {
    }

    /** Authenticates the request as the user named in a test header (stands in for the JWT filter). */
    public static WebFilter testAuthentication() {
        return (exchange, chain) -> {
            String user = exchange.getRequest().getHeaders().getFirst(USER_HEADER);
            if (user == null) {
                return chain.filter(exchange);
            }
            String scopes = exchange.getRequest().getHeaders().getFirst(SCOPES_HEADER);
            String[] authorities = (scopes == null ? "SCOPE_ticketing:purchase" : scopes).split(",");
            Principal principal = new TestingAuthenticationToken(user, null, authorities);
            return chain.filter(exchange.mutate().principal(Mono.just(principal)).build());
        };
    }

    public static RequestValidator validator() {
        return new RequestValidator(Validation.buildDefaultValidatorFactory().getValidator());
    }

    public static Event event() {
        return new Event(EVENT_ID, "Rock Fest", new Venue("Arena", "Bogotá"), NOW.plusSeconds(86_400), 100, NOW);
    }

    public static Order pendingOrder() {
        return Order.place(new OrderId("ord-1"), EVENT_ID, CUSTOMER, new Quantity(2), NOW, OrderPolicy.defaults());
    }

    public static Order reservedOrder() {
        return pendingOrder().reserve(List.of(new TicketId("t-1"), new TicketId("t-2")), NOW, OrderPolicy.defaults()).after();
    }
}
