package co.ticketing.config;

import co.ticketing.model.payment.gateways.PaymentGateway;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookupGateway;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import co.ticketing.dynamodb.DynamoDbSimulatedPaymentGateway;
import co.ticketing.usecase.payment.AuthorizePaymentUseCase;
import co.ticketing.usecase.payment.ReconcilePaymentAuthorizationUseCase;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;


import static org.assertj.core.api.Assertions.assertThat;

@Import(PaymentAuthorizationWiringTest.JwtTestConfig.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "ticketing.roles.api=false",
                "ticketing.roles.worker=false"
        }
)
class PaymentAuthorizationWiringTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void paymentInterfacesShareSameDurableAdapterInstance() {
        PaymentGateway payment =
                context.getBean(PaymentGateway.class);

        PaymentAuthorizationLookupGateway lookup =
                context.getBean(PaymentAuthorizationLookupGateway.class);

        DynamoDbSimulatedPaymentGateway simulator =
                context.getBean(DynamoDbSimulatedPaymentGateway.class);

        assertThat(payment).isSameAs(simulator);
        assertThat(lookup).isSameAs(simulator);
    }

    @Test
    void authorizationRepositoryAndUseCasesAreRegistered() {
        assertThat(context.getBean(
                PaymentAuthorizationRepository.class))
                .isNotNull();

        assertThat(context.getBean(
                AuthorizePaymentUseCase.class))
                .isNotNull();

        assertThat(context.getBean(
                ReconcilePaymentAuthorizationUseCase.class))
                .isNotNull();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfig {

        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.error(
                    new IllegalArgumentException(
                            "JWT decoder disabled in wiring test"));
        }
    }

}
