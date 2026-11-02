
package co.ticketing.config;

import co.ticketing.usecase.payment.RegisterPaymentCompensationUseCase;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.scheduler.OrderHousekeepingScheduler;
import co.ticketing.sqs.listener.ListenerSettings;
import co.ticketing.sqs.listener.PaymentRequestListener;
import co.ticketing.sqs.listener.ReservationRequestListener;
import co.ticketing.usecase.housekeeping.ReleaseExpiredReservationsUseCase;
import co.ticketing.usecase.housekeeping.RepublishStalledOrdersUseCase;
import co.ticketing.usecase.order.ProcessPaymentUseCase;
import co.ticketing.usecase.order.ReserveTicketsUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerConfigTest {

    private final WorkerConfig config = new WorkerConfig();

    private final TicketingProperties properties =
            Mockito.mock(TicketingProperties.class,
                    Mockito.RETURNS_DEEP_STUBS);

    @Test
    void shouldCreateListenerSettings() {
        Mockito.when(properties.listener().maxMessages()).thenReturn(10);
        Mockito.when(properties.listener().waitTimeSeconds()).thenReturn(20);
        Mockito.when(properties.listener().concurrency()).thenReturn(4);
        Mockito.when(properties.listener().processingTimeout())
                .thenReturn(Duration.ofSeconds(30));
        Mockito.when(properties.listener().errorBackoff())
                .thenReturn(Duration.ofSeconds(2));

        ListenerSettings result = config.listenerSettings(properties);

        assertThat(result).isNotNull();
    }

    @Test
    void shouldCreateReservationRequestListener() {
        Mockito.when(properties.queues().reservationRequestsUrl())
                .thenReturn("http://localhost:4566/reservation-requests");

        ReservationRequestListener result =
                config.reservationRequestListener(
                        Mockito.mock(SqsAsyncClient.class),
                        properties,
                        Mockito.mock(ListenerSettings.class),
                        new CommandMessageCodec(),
                        new SimpleMeterRegistry(),
                        Mockito.mock(ReserveTicketsUseCase.class)
                );

        assertThat(result).isNotNull();
    }

    @Test
    void shouldCreatePaymentRequestListener() {
        Mockito.when(properties.queues().paymentRequestsUrl())
                .thenReturn("http://localhost:4566/payment-requests");

        PaymentRequestListener result =
                config.paymentRequestListener(
                        Mockito.mock(SqsAsyncClient.class),
                        properties,
                        Mockito.mock(ListenerSettings.class),
                        new CommandMessageCodec(),
                        new SimpleMeterRegistry(),
                        Mockito.mock(ProcessPaymentUseCase.class), Mockito.mock(RegisterPaymentCompensationUseCase.class));

        assertThat(result).isNotNull();
    }

    @Test
    void shouldCreateHousekeepingScheduler() {
        Mockito.when(properties.scheduler().initialDelay())
                .thenReturn(Duration.ofSeconds(5));
        Mockito.when(properties.scheduler().interval())
                .thenReturn(Duration.ofSeconds(15));

        OrderHousekeepingScheduler result =
                config.orderHousekeepingScheduler(
                        Mockito.mock(ReleaseExpiredReservationsUseCase.class),
                        Mockito.mock(RepublishStalledOrdersUseCase.class),
                        properties,
                        new SimpleMeterRegistry()
                );

        assertThat(result).isNotNull();
    }
}
