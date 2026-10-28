package co.ticketing.sqs.publisher;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Publishes commands to SQS (at-least-once). Retry with backoff absorbs short glitches and the
 * circuit breaker fails fast while SQS is unavailable, so the API does not pile up waiting requests.
 */
public final class SqsOrderCommandPublisher implements OrderCommandPublisher {

    public static final String TYPE_ATTRIBUTE = "type";

    private final SqsAsyncClient sqs;
    private final SqsQueues queues;
    private final CommandMessageCodec codec;
    private final CircuitBreaker circuitBreaker;
    private final Duration timeout;

    public SqsOrderCommandPublisher(SqsAsyncClient sqs, SqsQueues queues, CommandMessageCodec codec,
                                    CircuitBreaker circuitBreaker, Duration timeout) {
        this.sqs = sqs;
        this.queues = queues;
        this.codec = codec;
        this.circuitBreaker = circuitBreaker;
        this.timeout = timeout;
    }

    @Override
    public Mono<Void> publish(OrderCommand command) {
        return Mono.fromCallable(() -> request(command))
                .flatMap(request -> Mono.fromFuture(() -> sqs.sendMessage(request)).timeout(timeout))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100)).filter(SqsOrderCommandPublisher::isRetryable))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .onErrorMap(error -> error instanceof TransientFailureException
                        ? error
                        : new TransientFailureException("Unable to publish " + CommandMessageCodec.typeOf(command), error))
                .then();
    }

    SendMessageRequest request(OrderCommand command) {
        String queueUrl = switch (command) {
            case ReserveTicketsCommand _ -> queues.reservationRequestsUrl();
            case ProcessPaymentCommand _ -> queues.paymentRequestsUrl();
        };
        return SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(codec.encode(command))
                .messageAttributes(Map.of(TYPE_ATTRIBUTE, MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(CommandMessageCodec.typeOf(command))
                        .build()))
                .build();
    }

    static boolean isRetryable(Throwable error) {
        return switch (error) {
            case SdkClientException _, TimeoutException _ -> true;
            case SqsException sqsError -> sqsError.isThrottlingException() || sqsError.statusCode() >= 500;
            default -> false;
        };
    }
}
