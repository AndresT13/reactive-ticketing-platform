package co.ticketing.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static co.ticketing.web.security.AuthenticatedUser.ADMIN_AUTHORITY;
import static co.ticketing.web.security.AuthenticatedUser.PURCHASE_AUTHORITY;

/**
 * OAuth2 resource server. In AWS the JWTs are issued by Amazon Cognito and validated through its JWKS
 * ({@code spring.security.oauth2.resourceserver.jwt.issuer-uri}); locally they are signed with an HS256
 * secret provided through the environment, never committed to the repository.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
public class SecurityConfig {

    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)          // stateless token-based API, no cookies
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/v1/events/*/availability", "/api/v1/events/*/availability/stream").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/v1/events", "/api/v1/events/*/complimentary-tickets").hasAuthority(ADMIN_AUTHORITY)
                        .pathMatchers(HttpMethod.GET, "/api/v1/events", "/api/v1/events/*").hasAnyAuthority(PURCHASE_AUTHORITY, ADMIN_AUTHORITY)
                        .pathMatchers("/api/v1/orders", "/api/v1/orders/**").hasAnyAuthority(PURCHASE_AUTHORITY, ADMIN_AUTHORITY)
                        .anyExchange().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Cognito encodes resource-server scopes as {@code ticketing/purchase}; the API authorities use
     * {@code ticketing:purchase}. Normalizing here keeps one authority name for both token issuers.
     */
    static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> scopes.convert(jwt).stream()
                .map(authority -> (GrantedAuthority) new SimpleGrantedAuthority(authority.getAuthority().replace('/', ':')))
                .toList());
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /** Local-only decoder (docker compose / tests). Production uses the issuer-uri auto-configuration. */
    @Bean
    @ConditionalOnProperty(name = "ticketing.security.local-jwt-secret")
    ReactiveJwtDecoder localJwtDecoder(@Value("${ticketing.security.local-jwt-secret}") String secret) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("ticketing.security.local-jwt-secret must have at least " + MIN_SECRET_BYTES + " bytes");
        }
        return NimbusReactiveJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
