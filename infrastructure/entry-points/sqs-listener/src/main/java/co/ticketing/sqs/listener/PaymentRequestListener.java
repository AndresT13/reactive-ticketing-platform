package co.ticketing.sqs.listener;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.usecase.order.PaymentOutcome;
import co.ticketing.usecase.order.ProcessPaymentUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Asynchronous adapter for the payment step. */
public final class PaymentRequestListener extends AbstractSqsListener {

    private final ProcessPaymentUseCase processPayment;

    public PaymentRequestListener(SqsAsyncClient sqs, String queueUrl, ListenerSettings settings, CommandMessageCodec codec,
                                  MeterRegistry meters, ProcessPaymentUseCase processPayment) {
        super(sqs, "payment-requests", queueUrl, settings, codec, meters);
        this.processPayment = processPayment;
    }

    @Override
    protected Mono<String> handle(OrderCommand command) {
        if (!(command instanceof ProcessPaymentCommand payment)) {
            return Mono.error(new IllegalArgumentException("Unexpected command on payment queue: " + command));
        }
        return processPayment.execute(payment).map(this::label);
    }

    String label(PaymentOutcome outcome) {
        return switch (outcome) {
            case PaymentOutcome.Sold _ -> "sold";
            case PaymentOutcome.Declined _ -> "declined";
            case PaymentOutcome.RefundRequired(var orderId, var authorizationId) -> {
                // Alert-worthy: the customer was charged for an order that had already been released.
                log.error("REFUND REQUIRED for order {} (authorization {})", orderId.value(), authorizationId);
                yield "refund_required";
            }
            case PaymentOutcome.AlreadyProcessed _ -> "duplicate";
        };
    }
}
