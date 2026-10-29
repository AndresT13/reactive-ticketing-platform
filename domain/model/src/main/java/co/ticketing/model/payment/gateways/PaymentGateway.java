package co.ticketing.model.payment.gateways;

import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import reactor.core.publisher.Mono;

/**
 * Port to the payment provider. Implementations must be idempotent by {@code orderId}.
 * Provider outages are signalled as {@link co.ticketing.model.common.exception.TransientFailureException}.
 */
@FunctionalInterface
public interface PaymentGateway {

    Mono<PaymentResult> authorize(PaymentRequest request);
}
