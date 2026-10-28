package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.Attributes;
import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.gateways.OrderRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

import java.time.Instant;
import java.util.Map;

public final class DynamoDbOrderRepository implements OrderRepository {

    private static final int MAX_PAGE_SIZE = 100;

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;
    private final OrderItemMapper mapper;

    public DynamoDbOrderRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables, OrderItemMapper mapper) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
        this.mapper = mapper;
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.orders())
                .key(OrderItemMapper.key(orderId))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> OrderItemMapper.toDomain(response.item()))
                .onErrorMap(DynamoDbErrors::translate);
    }

    @Override
    public Flux<Order> findDue(OrderStatus status, int bucket, Instant now, int limit) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tables.orders())
                .indexName(DynamoDbTables.DEADLINE_INDEX)
                .keyConditionExpression("#key = :key AND #deadline <= :now")
                .expressionAttributeNames(Map.of("#key", OrderItemMapper.DEADLINE_KEY, "#deadline", OrderItemMapper.DEADLINE_AT))
                .expressionAttributeValues(Map.of(
                        ":key", Attributes.s(mapper.deadlineKey(status, bucket)),
                        ":now", Attributes.instant(now)))
                .limit(Math.min(limit, MAX_PAGE_SIZE))
                .build();
        // The paginator is a Reactive Streams publisher: pages are requested on demand (backpressure),
        // and take(limit) cancels the upstream so no further pages are fetched.
        return Flux.from(dynamoDb.queryPaginator(request).items())
                .take(limit)
                .map(OrderItemMapper::toDomain)
                .onErrorMap(DynamoDbErrors::translate);
    }

    @Override
    public Mono<Void> postponeDeadline(OrderId orderId, Instant newDeadline) {
        // The version is intentionally not incremented: this is housekeeping metadata, not a business change.
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(tables.orders())
                .key(OrderItemMapper.key(orderId))
                .updateExpression("SET #deadline = :deadline")
                .conditionExpression("#status = :pending")
                .expressionAttributeNames(Map.of("#deadline", OrderItemMapper.DEADLINE_AT, "#status", "status"))
                .expressionAttributeValues(Map.of(
                        ":deadline", Attributes.instant(newDeadline),
                        ":pending", Attributes.s(OrderStatus.PENDING.name())))
                .build();
        return Mono.fromFuture(() -> dynamoDb.updateItem(request))
                .then()
                .onErrorResume(ConditionalCheckFailedException.class, error -> Mono.empty())
                .onErrorMap(DynamoDbErrors::translate);
    }
}
