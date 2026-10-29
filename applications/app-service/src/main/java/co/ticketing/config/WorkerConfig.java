package co.ticketing.config;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.scheduler.OrderHousekeepingScheduler;
import co.ticketing.sqs.listener.ListenerSettings;
import co.ticketing.sqs.listener.PaymentRequestListener;
import co.ticketing.sqs.listener.ReservationRequestListener;
import co.ticketing.usecase.housekeeping.ReleaseExpiredReservationsUseCase;
import co.ticketing.usecase.housekeeping.RepublishStalledOrdersUseCase;
import co.ticketing.usecase.order.ProcessPaymentUseCase;
import co.ticketing.usecase.order.ReserveTicketsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

/** Worker role: asynchronous entry points. Disabled on API-only instances ({@code ticketing.roles.worker=false}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ticketing.roles.worker", havingValue = "true")
public class WorkerConfig {

    @Bean
    ListenerSettings listenerSettings(TicketingProperties properties) {
        TicketingProperties.Listener listener = properties.listener();
        return new ListenerSettings(listener.maxMessages(), listener.waitTimeSeconds(), listener.concurrency(),
                listener.processingTimeout(), listener.errorBackoff());
    }

    @Bean
    ReservationRequestListener reservationRequestListener(SqsAsyncClient sqs, TicketingProperties properties,
                                                          ListenerSettings settings, CommandMessageCodec codec,
                                                          MeterRegistry meters, ReserveTicketsUseCase reserveTickets) {
        return new ReservationRequestListener(sqs, properties.queues().reservationRequestsUrl(), settings, codec, meters, reserveTickets);
    }

    @Bean
    PaymentRequestListener paymentRequestListener(SqsAsyncClient sqs, TicketingProperties properties,
                                                  ListenerSettings settings, CommandMessageCodec codec,
                                                  MeterRegistry meters, ProcessPaymentUseCase processPayment) {
        return new PaymentRequestListener(sqs, properties.queues().paymentRequestsUrl(), settings, codec, meters, processPayment);
    }

    @Bean
    OrderHousekeepingScheduler orderHousekeepingScheduler(ReleaseExpiredReservationsUseCase releaseExpired,
                                                          RepublishStalledOrdersUseCase republishStalled,
                                                          TicketingProperties properties, MeterRegistry meters) {
        return new OrderHousekeepingScheduler(releaseExpired, republishStalled,
                properties.scheduler().initialDelay(), properties.scheduler().interval(), meters);
    }
}
