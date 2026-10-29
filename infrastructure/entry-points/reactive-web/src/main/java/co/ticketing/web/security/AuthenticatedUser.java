package co.ticketing.web.security;

import co.ticketing.model.common.CustomerId;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Caller identity. The customer id ALWAYS comes from the token subject, never from the request body
 * (OWASP A01: broken access control).
 */
public record AuthenticatedUser(CustomerId customerId, boolean admin) {

    public static final String ADMIN_AUTHORITY = "SCOPE_ticketing:admin";
    public static final String PURCHASE_AUTHORITY = "SCOPE_ticketing:purchase";

    public static Mono<AuthenticatedUser> from(ServerRequest request) {
        return request.principal()
                .filter(Authentication.class::isInstance)
                .cast(Authentication.class)
                .map(AuthenticatedUser::of)
                .switchIfEmpty(Mono.error(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required")));
    }

    static AuthenticatedUser of(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(ADMIN_AUTHORITY::equals);
        return new AuthenticatedUser(new CustomerId(authentication.getName()), admin);
    }
}
