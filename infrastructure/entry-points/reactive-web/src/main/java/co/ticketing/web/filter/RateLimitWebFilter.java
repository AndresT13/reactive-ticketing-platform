package co.ticketing.web.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Limits purchase requests (POST /api/v1/orders/**) per client before authentication runs, so floods are
 * rejected cheaply. The key is the bearer token when present, otherwise the remote address.
 */
public final class RateLimitWebFilter implements WebFilter, Ordered {

    private static final String PROTECTED_PREFIX = "/api/v1/orders";
    private static final byte[] BODY = ("{\"type\":\"about:blank\",\"title\":\"Rate limited\",\"status\":429,"
            + "\"detail\":\"Too many purchase requests, slow down\",\"code\":\"RATE_LIMITED\"}").getBytes(StandardCharsets.UTF_8);

    private final TokenBucketRateLimiter limiter;

    public RateLimitWebFilter(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean protectedCall = HttpMethod.POST.equals(exchange.getRequest().getMethod())
                && exchange.getRequest().getPath().value().startsWith(PROTECTED_PREFIX);
        if (!protectedCall || limiter.tryAcquire(clientKey(exchange))) {
            return chain.filter(exchange);
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return response.writeWith(Mono.just(response.bufferFactory().wrap(BODY)));
    }

    static String clientKey(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            return "token:" + Integer.toHexString(authorization.hashCode());
        }
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        return "ip:" + (remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress());
    }

    @Override
    public int getOrder() {
        return -200;   // after the correlation id (-300), before Spring Security (-100)
    }
}
