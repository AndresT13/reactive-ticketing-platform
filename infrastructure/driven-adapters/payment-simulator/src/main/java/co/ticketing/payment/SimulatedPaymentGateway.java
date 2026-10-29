package co.ticketing.payment;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.gateways.PaymentGateway;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Deterministic stand-in for a payment provider, used locally and in tests:
 * <ul>
 *   <li>tokens starting with {@code tok_decline} are declined;</li>
 *   <li>tokens starting with {@code tok_unavailable} simulate a provider outage (transient failure);</li>
 *   <li>any other token is approved.</li>
 * </ul>
 * The authorization id derives from the order id, mimicking a provider that is idempotent by order.
 */
public final class SimulatedPaymentGateway implements PaymentGateway {

    public static final String DECLINE_PREFIX = "tok_decline";
    public static final String UNAVAILABLE_PREFIX = "tok_unavailable";

    private final Duration latency;

    public SimulatedPaymentGateway(Duration latency) {
        this.latency = latency;
    }

    @Override
    public Mono<PaymentResult> authorize(PaymentRequest request) {
        return Mono.delay(latency).then(Mono.defer(() -> decide(request)));
    }

    private static Mono<PaymentResult> decide(PaymentRequest request) {
        String token = request.paymentToken();
        if (token.startsWith(UNAVAILABLE_PREFIX)) {
            return Mono.error(new TransientFailureException("Payment provider unavailable"));
        }
        if (token.startsWith(DECLINE_PREFIX)) {
            return Mono.just(new PaymentResult.Declined("CARD_DECLINED"));
        }
        return Mono.just(new PaymentResult.Approved("auth-" + request.orderId().value()));
    }
}
