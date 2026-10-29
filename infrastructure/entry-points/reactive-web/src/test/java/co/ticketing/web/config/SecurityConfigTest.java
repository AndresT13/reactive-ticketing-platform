package co.ticketing.web.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies the authorization rules with real HS256-signed JWTs through the actual security filter chain. */
class SecurityConfigTest {

    private static final String SECRET = "local-test-secret-with-at-least-32-bytes!!";

    private AnnotationConfigApplicationContext context;
    private WebTestClient client;

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    static class Routes {
        @Bean
        RouterFunction<ServerResponse> routes() {
            return RouterFunctions.route()
                    .GET("/api/v1/events/{id}/availability", request -> ServerResponse.ok().build())
                    .GET("/api/v1/orders/{id}", request -> ServerResponse.ok().build())
                    .POST("/api/v1/events", request -> ServerResponse.ok().build())
                    .GET("/internal", request -> ServerResponse.ok().build())
                    .build();
        }
    }

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("ticketing.security.local-jwt-secret", SECRET)));
        context.register(Routes.class, SecurityConfig.class);
        context.refresh();
        client = WebTestClient.bindToApplicationContext(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void availabilityIsPublic() {
        client.get().uri("/api/v1/events/e-1/availability").exchange().expectStatus().isOk();
    }

    @Test
    void cognitoStyleScopesAreNormalized() {
        client.get().uri("/api/v1/orders/o-1").headers(h -> h.setBearerAuth(token("ticketing/purchase", SECRET)))
                .exchange().expectStatus().isOk();
        client.post().uri("/api/v1/events").headers(h -> h.setBearerAuth(token("ticketing/admin", SECRET)))
                .exchange().expectStatus().isOk();
    }

    @Test
    void ordersRequireAValidToken() {
        client.get().uri("/api/v1/orders/o-1").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders/o-1").headers(h -> h.setBearerAuth("not-a-jwt")).exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/v1/orders/o-1").headers(h -> h.setBearerAuth(token("ticketing:purchase", SECRET)))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/v1/orders/o-1").headers(h -> h.setBearerAuth(token("ticketing:purchase", SECRET.replace('l', 'x'))))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void creatingEventsRequiresTheAdminScope() {
        client.post().uri("/api/v1/events").headers(h -> h.setBearerAuth(token("ticketing:purchase", SECRET)))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/v1/events").headers(h -> h.setBearerAuth(token("ticketing:admin", SECRET)))
                .exchange().expectStatus().isOk();
    }

    @Test
    void everythingElseIsDenied() {
        client.get().uri("/internal").headers(h -> h.setBearerAuth(token("ticketing:admin", SECRET)))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void localSecretsMustBeLongEnough() {
        assertThatThrownBy(() -> new SecurityConfig().localJwtDecoder("short")).isInstanceOf(IllegalStateException.class);
    }

    private static String token(String scope, String secret) {
        try {
            return signedToken(scope, secret);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String signedToken(String scope, String secret) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("customer-1")
                .claim("scope", scope)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }
}
