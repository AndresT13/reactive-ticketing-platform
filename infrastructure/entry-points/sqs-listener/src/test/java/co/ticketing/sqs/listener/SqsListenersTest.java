package co.ticketing.sqs.listener;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.ProcessPaymentCommand;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.RejectionReason;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.usecase.order.PaymentOutcome;
import co.ticketing.usecase.order.ProcessPaymentUseCase;
import co.ticketing.usecase.order.ReservationOutcome;
import co.ticketing.usecase.order.ReserveTicketsUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsListenersTest {

    private static final Instant AT = Instant.parse("2026-10-27T20:00:00Z");
    private static final OrderId ORDER_ID = new OrderId("ord-1");
    private static final ListenerSettings SETTINGS = new ListenerSettings(10, 0, 4, Duration.ofSeconds(5), Duration.ofMillis(10));

    private final SqsAsyncClient sqs = mock(SqsAsyncClient.class);
    private final ReserveTicketsUseCase reserveTickets = mock(ReserveTicketsUseCase.class);
    private final ProcessPaymentUseCase processPayment = mock(ProcessPaymentUseCase.class);
    private final CommandMessageCodec codec = new CommandMessageCodec();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private ReservationRequestListener reservations;
    private PaymentRequestListener payments;

    @BeforeEach
    void setUp() {
        reservations = new ReservationRequestListener(sqs, "http://sqs/reservations", SETTINGS, codec, meters, reserveTickets);
        payments = new PaymentRequestListener(sqs, "http://sqs/payments", SETTINGS, codec, meters, processPayment);
        when(sqs.deleteMessage(any(DeleteMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(DeleteMessageResponse.builder().build()));
    }

    private Message reserveMessage() {
        return message(codec.encode(new ReserveTicketsCommand(ORDER_ID, new EventId("evt-1"), new Quantity(1), AT)));
    }

    private static Message message(String body) {
        return Message.builder().messageId("m-1").receiptHandle("rh-1").body(body).build();
    }

    private static Order order() {
        return Order.place(ORDER_ID, new EventId("evt-1"), new CustomerId("c-1"), new Quantity(1), AT, OrderPolicy.defaults());
    }

    @Test
    void acknowledgesOnlyAfterSuccessfulProcessing() {
        when(reserveTickets.execute(ORDER_ID)).thenReturn(Mono.just(new ReservationOutcome.Reserved(order())));

        StepVerifier.create(reservations.process(reserveMessage())).expectNext("reserved").verifyComplete();

        verify(sqs).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(meters.counter("ticketing.messages", "queue", "reservation-requests", "outcome", "reserved").count()).isEqualTo(1);
    }

    @Test
    void technicalFailuresLeaveTheMessageForRedelivery() {
        when(reserveTickets.execute(ORDER_ID)).thenReturn(Mono.error(new TransientFailureException("throttled")));

        StepVerifier.create(reservations.process(reserveMessage())).verifyComplete();

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        assertThat(meters.counter("ticketing.messages", "queue", "reservation-requests", "outcome", "failed").count()).isEqualTo(1);
    }

    @Test
    void poisonAndMisroutedMessagesAreNeverAcknowledged() {
        StepVerifier.create(reservations.process(message("garbage"))).verifyComplete();
        StepVerifier.create(reservations.process(message(codec.encode(new ProcessPaymentCommand(ORDER_ID, "tok", AT))))).verifyComplete();
        StepVerifier.create(payments.process(reserveMessage())).verifyComplete();

        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void consumesReceivedMessages() {
        when(reserveTickets.execute(ORDER_ID)).thenReturn(Mono.just(new ReservationOutcome.AlreadyProcessed(ORDER_ID)));
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(reserveMessage()).build()));

        StepVerifier.create(reservations.consume(Mono.never()).take(1)).expectNext("duplicate").verifyComplete();
    }

    @Test
    void receiveFailuresBackOffInsteadOfCrashing() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(SdkClientException.create("network")));

        StepVerifier.create(reservations.receiveBatch()).verifyComplete();
    }

    @Test
    void gracefulShutdownStopsReceivingAndReportsCompletion() throws InterruptedException {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(ReceiveMessageResponse.builder().messages(List.of()).build()));
        CountDownLatch stopped = new CountDownLatch(1);

        reservations.start();
        assertThat(reservations.isRunning()).isTrue();
        reservations.stop(stopped::countDown);

        assertThat(stopped.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reservations.isRunning()).isFalse();
        CountDownLatch again = new CountDownLatch(1);
        reservations.stop(again::countDown);           // stopping a stopped listener completes immediately
        assertThat(again.getCount()).isZero();
        reservations.stop();
    }

    @Test
    void labelsBusinessOutcomes() {
        Order order = order();
        assertThat(ReservationRequestListener.label(new ReservationOutcome.Rejected(order, RejectionReason.SOLD_OUT)))
                .isEqualTo("rejected_sold_out");
        assertThat(payments.label(new PaymentOutcome.Sold(order))).isEqualTo("sold");
        assertThat(payments.label(new PaymentOutcome.Declined(order, "CARD_DECLINED"))).isEqualTo("declined");
        assertThat(payments.label(new PaymentOutcome.RefundRequired(ORDER_ID, "auth-1"))).isEqualTo("refund_required");
        assertThat(payments.label(new PaymentOutcome.AlreadyProcessed(ORDER_ID))).isEqualTo("duplicate");
    }

    @Test
    void processesPayments() {
        when(processPayment.execute(any())).thenReturn(Mono.just(new PaymentOutcome.Sold(order())));

        StepVerifier.create(payments.process(message(codec.encode(new ProcessPaymentCommand(ORDER_ID, "tok", AT)))))
                .expectNext("sold")
                .verifyComplete();
    }

    @Test
    void settingsAreValidated() {
        assertThatThrownBy(() -> new ListenerSettings(11, 20, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListenerSettings(10, 21, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListenerSettings(10, 20, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
