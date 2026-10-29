package co.ticketing.web.filter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltersTest {

    private static final WebFilterChain PASS = exchange -> Mono.empty();

    @Test
    void tokenBucketAllowsBurstThenRefills() {
        AtomicLong nanos = new AtomicLong();
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(2, 1, nanos::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThat(limiter.tryAcquire("b")).isTrue();     // buckets are per key

        nanos.addAndGet(1_000_000_000L);                  // one second later: one token refilled
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        assertThatThrownBy(() -> new TokenBucketRateLimiter(0, 1, nanos::get)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rateLimitsOnlyPurchaseRequests() {
        RateLimitWebFilter filter = new RateLimitWebFilter(new TokenBucketRateLimiter(1, 0.001, System::nanoTime));

        MockServerWebExchange first = post("/api/v1/orders");
        StepVerifier.create(filter.filter(first, PASS)).verifyComplete();
        assertThat(first.getResponse().getStatusCode()).isNull();

        MockServerWebExchange second = post("/api/v1/orders");
        StepVerifier.create(filter.filter(second, PASS)).verifyComplete();
        assertThat(second.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

        MockServerWebExchange read = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders/o-1").header(HttpHeaders.AUTHORIZATION, "Bearer t"));
        StepVerifier.create(filter.filter(read, PASS)).verifyComplete();
        assertThat(read.getResponse().getStatusCode()).isNull();
        assertThat(filter.getOrder()).isLessThan(-100);
    }

    @Test
    void clientKeyPrefersTheBearerTokenThenTheRemoteAddress() {
        MockServerWebExchange withToken = post("/api/v1/orders");
        MockServerWebExchange anonymous = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders")
                .remoteAddress(new InetSocketAddress("10.0.0.7", 5555)));
        MockServerWebExchange unknown = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/orders"));

        assertThat(RateLimitWebFilter.clientKey(withToken)).startsWith("token:");
        assertThat(RateLimitWebFilter.clientKey(anonymous)).isEqualTo("ip:10.0.0.7");
        assertThat(RateLimitWebFilter.clientKey(unknown)).startsWith("ip:");
    }

    @Test
    void correlationIdIsGeneratedWhenMissingOrInvalid() {
        CorrelationIdWebFilter filter = new CorrelationIdWebFilter();
        MockServerWebExchange invalid = MockServerWebExchange.from(MockServerHttpRequest.get("/x")
                .header(CorrelationIdWebFilter.HEADER, "<script>"));

        StepVerifier.create(filter.filter(invalid, PASS)).verifyComplete();

        String generated = invalid.getResponse().getHeaders().getFirst(CorrelationIdWebFilter.HEADER);
        assertThat(generated).isNotEqualTo("<script>").hasSize(36);
        assertThat(invalid.<String>getAttribute(CorrelationIdWebFilter.ATTRIBUTE)).isEqualTo(generated);
        assertThat(filter.getOrder()).isEqualTo(-300);
    }

    private static MockServerWebExchange post(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).header(HttpHeaders.AUTHORIZATION, "Bearer same-token"));
    }
}
