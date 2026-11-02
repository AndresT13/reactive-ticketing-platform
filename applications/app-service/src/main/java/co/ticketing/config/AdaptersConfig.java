package co.ticketing.config;

import co.ticketing.dynamodb.DynamoDbEventRepository;
import co.ticketing.dynamodb.DynamoDbIdempotencyStore;
import co.ticketing.dynamodb.DynamoDbInventoryRepository;
import co.ticketing.dynamodb.DynamoDbOrderAdmissionStore;
import co.ticketing.dynamodb.DynamoDbOrderRepository;
import co.ticketing.dynamodb.DynamoDbOrderTransitionStore;
import co.ticketing.dynamodb.DynamoDbTables;
import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.order.OrderPolicy;
import co.ticketing.dynamodb.DynamoDbSimulatedPaymentGateway;
import co.ticketing.payment.SimulatedPaymentRefundGateway;
import co.ticketing.sqs.publisher.SqsOrderCommandPublisher;
import co.ticketing.sqs.publisher.SqsQueues;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import co.ticketing.dynamodb.DynamoDbPaymentCompensationRepository;
import co.ticketing.dynamodb.DynamoDbPaymentAuthorizationRepository;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** Wires every driven adapter (the implementations of the domain gateways). */
@Configuration(proxyBeanMethods = false)
public class AdaptersConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return () -> UUID.randomUUID().toString();
    }

    @Bean
    OrderPolicy orderPolicy(TicketingProperties properties) {
        TicketingProperties.Orders orders = properties.orders();
        return new OrderPolicy(orders.reservationTtl(), orders.paymentTimeout(), orders.pendingRedispatchAfter(),
                orders.deadlineBuckets(), orders.idempotencyTtl());
    }

    @Bean
    DynamoDbTables dynamoDbTables(TicketingProperties properties) {
        TicketingProperties.Tables tables = properties.tables();
        return new DynamoDbTables(tables.events(), tables.inventory(), tables.orders(), tables.tickets(), tables.audit(),
                tables.idempotency(), tables.guards());
    }

    @Bean
    OrderItemMapper orderItemMapper(OrderPolicy policy) {
        return new OrderItemMapper(policy);
    }

    @Bean
    DynamoDbEventRepository eventRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        return new DynamoDbEventRepository(dynamoDb, tables);
    }

    @Bean
    DynamoDbInventoryRepository inventoryRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        return new DynamoDbInventoryRepository(dynamoDb, tables);
    }

    @Bean
    DynamoDbOrderRepository orderRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables, OrderItemMapper mapper) {
        return new DynamoDbOrderRepository(dynamoDb, tables, mapper);
    }

    @Bean
    DynamoDbIdempotencyStore idempotencyStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        return new DynamoDbIdempotencyStore(dynamoDb, tables);
    }

    @Bean
    DynamoDbOrderAdmissionStore orderAdmissionStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables,
                                                    OrderItemMapper mapper, DynamoDbIdempotencyStore idempotencyStore) {
        return new DynamoDbOrderAdmissionStore(dynamoDb, tables, mapper, idempotencyStore);
    }

    @Bean
    DynamoDbOrderTransitionStore orderTransitionStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables, OrderItemMapper mapper) {
        return new DynamoDbOrderTransitionStore(dynamoDb, tables, mapper);
    }

    @Bean
    CommandMessageCodec commandMessageCodec() {
        return new CommandMessageCodec();
    }

    @Bean
    CircuitBreaker sqsPublisherCircuitBreaker() {
        return CircuitBreaker.of("sqs-publisher", CircuitBreakerConfig.custom()
                .slidingWindowSize(50)
                .minimumNumberOfCalls(20)
                .failureRateThreshold(50)
                .slowCallDurationThreshold(Duration.ofSeconds(2))
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
    }

    @Bean
    SqsOrderCommandPublisher orderCommandPublisher(SqsAsyncClient sqs, TicketingProperties properties,
                                                   CommandMessageCodec codec, CircuitBreaker sqsPublisherCircuitBreaker) {
        TicketingProperties.Queues queues = properties.queues();
        return new SqsOrderCommandPublisher(sqs, new SqsQueues(queues.reservationRequestsUrl(), queues.paymentRequestsUrl()),
                codec, sqsPublisherCircuitBreaker, queues.publishTimeout());
    }


    @Bean
    SimulatedPaymentRefundGateway paymentRefundGateway(
            TicketingProperties properties
    ) {
        return new SimulatedPaymentRefundGateway(
                properties.payment().simulatedLatency()
        );
    }

    @Bean
    DynamoDbSimulatedPaymentGateway paymentGateway(
            DynamoDbAsyncClient dynamoDb,
            TicketingProperties properties,
            @Value("${ticketing.tables.payment-provider-results:ticketing-payment-provider-results}")
            String tableName) {
        return new DynamoDbSimulatedPaymentGateway(
                dynamoDb,
                tableName,
                properties.payment().simulatedLatency());
    }


    @Bean
    DynamoDbPaymentCompensationRepository paymentCompensationRepository(
            DynamoDbAsyncClient dynamoDb,
            TicketingProperties properties
    ) {
        return new DynamoDbPaymentCompensationRepository(
                dynamoDb,
                properties.tables().paymentCompensations()
        );
    }


    @Bean
    DynamoDbPaymentAuthorizationRepository paymentAuthorizationRepository(
            DynamoDbAsyncClient dynamoDb,
            @Value("${ticketing.tables.payment-authorizations}")
            String tableName) {
        return new DynamoDbPaymentAuthorizationRepository(
                dynamoDb, tableName);
    }

}
