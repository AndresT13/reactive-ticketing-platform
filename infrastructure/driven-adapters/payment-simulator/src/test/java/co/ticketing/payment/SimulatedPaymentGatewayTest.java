package co.ticketing.payment;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;

class SimulatedPaymentGatewayTest {

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(Duration.ZERO);

    private static PaymentRequest request(String token) {
        return new PaymentRequest(new OrderId("o-1"), new CustomerId("c-1"), new Quantity(1), token);
    }

    @Test
    void approvesRegularTokensIdempotentlyByOrder() {
        StepVerifier.create(gateway.authorize(request("tok_visa")))
                .expectNext(new PaymentResult.Approved("auth-o-1"))
                .verifyComplete();
    }

    @Test
    void declinesDeclineTokens() {
        StepVerifier.create(gateway.authorize(request("tok_decline_insufficient_funds")))
                .expectNext(new PaymentResult.Declined("CARD_DECLINED"))
                .verifyComplete();
    }

    @Test
    void simulatesProviderOutages() {
        StepVerifier.create(gateway.authorize(request("tok_unavailable")))
                .verifyError(TransientFailureException.class);
    }
}
