package co.ticketing;

import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.web.handler.OrderHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole application in the API role (no worker, no AWS): verifies the wiring, the security
 * chain and that an unreachable data store degrades to 503 instead of hanging or leaking errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ticketing.roles.api=true",
        "ticketing.roles.worker=false",
        "ticketing.security.local-jwt-secret=context-test-secret-with-at-least-32-bytes",
        "ticketing.aws.dynamodb-endpoint=http://127.0.0.1:1",
        "ticketing.aws.sqs-endpoint=http://127.0.0.1:1",
        "ticketing.aws.api-call-timeout=1s",
        "ticketing.aws.api-call-attempt-timeout=500ms"
})
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .responseTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Test
    void wiresTheApiRoleOnly() {
        assertThat(context.getBean(PlaceOrderUseCase.class)).isNotNull();
        assertThat(context.getBean(OrderHandler.class)).isNotNull();
        assertThat(context.getBeanNamesForType(co.ticketing.sqs.listener.ReservationRequestListener.class)).isEmpty();
    }

    @Test
    void healthIsPublic() {
        client().get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void ordersRequireAuthentication() {
        client().get().uri("/api/v1/orders/ord-1").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void unreachableDataStoreDegradesToServiceUnavailable() {
        client().get().uri("/api/v1/events/evt-1/availability").exchange()
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
}
