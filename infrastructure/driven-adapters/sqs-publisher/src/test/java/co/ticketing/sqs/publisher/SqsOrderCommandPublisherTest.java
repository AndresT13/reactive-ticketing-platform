package co.ticketing.sqs.publisher;

import co.ticketing.messaging.CommandMessage;
import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.ReserveTicketsCommand;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SqsOrderCommandPublisherTest {

    private static final Instant AT = Instant.parse("2026-10-27T20:00:00Z");
    private static final SqsQueues QUEUES = new SqsQueues("http://sqs/reservations", "http://sqs/payments");

    @Mock private SqsAsyncClient sqs;

    private final ReserveTicketsCommand reserve = new ReserveTicketsCommand(new OrderId("o-1"), new EventId("e-1"), new Quantity(2), AT);

    private SqsOrderCommandPublisher publisher(CircuitBreaker breaker) {
        return new SqsOrderCommandPublisher(sqs, QUEUES, new CommandMessageCodec(), breaker, Duration.ofSeconds(2));
    }

    @Test
    void publishesReservationCommandsToTheReservationQueue() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().messageId("m-1").build()));

        StepVerifier.create(publisher(CircuitBreaker.ofDefaults("test")).publish(reserve)).verifyComplete();

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(request.capture());
        assertThat(request.getValue().queueUrl()).isEqualTo(QUEUES.reservationRequestsUrl());
        assertThat(request.getValue().messageAttributes().get(SqsOrderCommandPublisher.TYPE_ATTRIBUTE).stringValue())
                .isEqualTo(CommandMessage.RESERVE_TICKETS);
        assertThat(new CommandMessageCodec().decode(request.getValue().messageBody())).isEqualTo(reserve);
    }

    @Test
    void paymentCommandsGoToThePaymentQueue() {
        SendMessageRequest request = publisher(CircuitBreaker.ofDefaults("test"))
                .request(new ProcessPaymentCommand(new OrderId("o-1"), "tok", AT));

        assertThat(request.queueUrl()).isEqualTo(QUEUES.paymentRequestsUrl());
    }

    @Test
    void retriesTransientNetworkErrors() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("connection reset")))
                .thenReturn(CompletableFuture.completedFuture(SendMessageResponse.builder().build()));

        StepVerifier.create(publisher(CircuitBreaker.ofDefaults("test")).publish(reserve)).verifyComplete();
        verify(sqs, times(2)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void nonRetryableErrorsBecomeTransientFailuresForTheCaller() {
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SqsException.builder().statusCode(400).message("bad").build()));

        StepVerifier.create(publisher(CircuitBreaker.ofDefaults("test")).publish(reserve))
                .verifyError(TransientFailureException.class);
        verify(sqs, times(1)).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void openCircuitFailsFastWithoutCallingSqs() {
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("open");
        breaker.transitionToOpenState();

        StepVerifier.create(publisher(breaker).publish(reserve)).verifyError(TransientFailureException.class);
        verify(sqs, never()).sendMessage(any(SendMessageRequest.class));
    }

    @Test
    void classifiesRetryableErrors() {
        assertThat(SqsOrderCommandPublisher.isRetryable(new TimeoutException())).isTrue();
        assertThat(SqsOrderCommandPublisher.isRetryable(SqsException.builder().statusCode(503).build())).isTrue();
        assertThat(SqsOrderCommandPublisher.isRetryable(SqsException.builder().statusCode(400).build())).isFalse();
        assertThat(SqsOrderCommandPublisher.isRetryable(new IllegalStateException())).isFalse();
    }
}
