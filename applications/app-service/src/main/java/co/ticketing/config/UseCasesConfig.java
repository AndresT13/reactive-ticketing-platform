package co.ticketing.config;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.idempotency.gateways.IdempotencyStore;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.model.order.gateways.OrderAdmissionStore;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.payment.gateways.PaymentGateway;
import co.ticketing.usecase.availability.GetAvailabilityUseCase;
import co.ticketing.usecase.complimentary.IssueComplimentaryTicketsUseCase;
import co.ticketing.usecase.event.CreateEventUseCase;
import co.ticketing.usecase.event.GetEventUseCase;
import co.ticketing.usecase.event.ListEventsUseCase;
import co.ticketing.usecase.housekeeping.ReleaseExpiredReservationsUseCase;
import co.ticketing.usecase.housekeeping.RepublishStalledOrdersUseCase;
import co.ticketing.usecase.order.CancelOrderUseCase;
import co.ticketing.usecase.order.ConfirmOrderUseCase;
import co.ticketing.usecase.order.GetOrderUseCase;
import co.ticketing.usecase.order.PlaceOrderUseCase;
import co.ticketing.usecase.order.ProcessPaymentUseCase;
import co.ticketing.usecase.order.ReserveTicketsUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Use cases are plain Java classes (no framework annotations): they are instantiated here and receive the
 * gateway implementations through their constructors (dependency inversion).
 */
@Configuration(proxyBeanMethods = false)
public class UseCasesConfig {

    @Bean
    CreateEventUseCase createEventUseCase(EventRepository events, IdGenerator ids, Clock clock) {
        return new CreateEventUseCase(events, ids, clock);
    }

    @Bean
    GetEventUseCase getEventUseCase(EventRepository events) {
        return new GetEventUseCase(events);
    }

    @Bean
    ListEventsUseCase listEventsUseCase(EventRepository events) {
        return new ListEventsUseCase(events);
    }

    @Bean
    GetAvailabilityUseCase getAvailabilityUseCase(InventoryRepository inventories) {
        return new GetAvailabilityUseCase(inventories);
    }

    @Bean
    PlaceOrderUseCase placeOrderUseCase(EventRepository events, InventoryRepository inventories, IdempotencyStore idempotency,
                                        OrderAdmissionStore admissions, OrderRepository orders, OrderCommandPublisher publisher,
                                        IdGenerator ids, Clock clock, OrderPolicy policy) {
        return new PlaceOrderUseCase(events, inventories, idempotency, admissions, orders, publisher, ids, clock, policy);
    }

    @Bean
    ReserveTicketsUseCase reserveTicketsUseCase(OrderRepository orders, OrderTransitionStore store, IdGenerator ids,
                                                Clock clock, OrderPolicy policy) {
        return new ReserveTicketsUseCase(orders, store, ids, clock, policy);
    }

    @Bean
    ConfirmOrderUseCase confirmOrderUseCase(OrderRepository orders, OrderTransitionStore store,
                                            OrderCommandPublisher publisher, Clock clock, OrderPolicy policy) {
        return new ConfirmOrderUseCase(orders, store, publisher, clock, policy);
    }

    @Bean
    ProcessPaymentUseCase processPaymentUseCase(OrderRepository orders, OrderTransitionStore store,
                                                PaymentGateway payments, Clock clock) {
        return new ProcessPaymentUseCase(orders, store, payments, clock);
    }

    @Bean
    CancelOrderUseCase cancelOrderUseCase(OrderRepository orders, OrderTransitionStore store, Clock clock) {
        return new CancelOrderUseCase(orders, store, clock);
    }

    @Bean
    GetOrderUseCase getOrderUseCase(OrderRepository orders) {
        return new GetOrderUseCase(orders);
    }

    @Bean
    IssueComplimentaryTicketsUseCase issueComplimentaryTicketsUseCase(EventRepository events, OrderTransitionStore store,
                                                                      IdGenerator ids, Clock clock) {
        return new IssueComplimentaryTicketsUseCase(events, store, ids, clock);
    }

    @Bean
    ReleaseExpiredReservationsUseCase releaseExpiredReservationsUseCase(OrderRepository orders, OrderTransitionStore store,
                                                                        Clock clock, OrderPolicy policy,
                                                                        TicketingProperties properties) {
        return new ReleaseExpiredReservationsUseCase(orders, store, clock, policy, properties.orders().sweepBatchSize());
    }

    @Bean
    RepublishStalledOrdersUseCase republishStalledOrdersUseCase(OrderRepository orders, OrderCommandPublisher publisher,
                                                                Clock clock, OrderPolicy policy,
                                                                TicketingProperties properties) {
        return new RepublishStalledOrdersUseCase(orders, publisher, clock, policy, properties.orders().sweepBatchSize());
    }
}
