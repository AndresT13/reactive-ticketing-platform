package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.EventItemMapper;
import co.ticketing.dynamodb.mapper.InventoryItemMapper;
import co.ticketing.model.common.Page;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.EventId;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.inventory.Inventory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;

public final class DynamoDbEventRepository implements EventRepository {

    private static final String NOT_EXISTS = "attribute_not_exists(" + EventItemMapper.KEY + ")";

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;

    public DynamoDbEventRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
    }

    @Override
    public Mono<Event> findById(EventId id) {
        // Catalog reads are eventually consistent: half the cost, and an event never changes after creation.
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.events())
                .key(EventItemMapper.key(id))
                .build();
        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> EventItemMapper.toDomain(response.item()))
                .onErrorMap(DynamoDbErrors::translate);
    }

    @Override
    public Mono<Page<Event>> findAll(int limit, String cursor) {
        return Mono.fromCallable(() -> scanRequest(limit, cursor))
                .flatMap(request -> Mono.fromFuture(() -> dynamoDb.scan(request)))
                .map(DynamoDbEventRepository::toPage)
                .onErrorMap(DynamoDbErrors::translate);
    }

    @Override
    public Mono<Void> create(Event event, Inventory inventory) {
        TransactWriteItemsRequest request = TransactWriteItemsRequest.builder().transactItems(
                TransactWriteItem.builder().put(Put.builder()
                        .tableName(tables.events())
                        .item(EventItemMapper.toItem(event))
                        .conditionExpression(NOT_EXISTS)
                        .build()).build(),
                TransactWriteItem.builder().put(Put.builder()
                        .tableName(tables.inventory())
                        .item(InventoryItemMapper.toItem(inventory, event.createdAt()))
                        .conditionExpression("attribute_not_exists(" + InventoryItemMapper.KEY + ")")
                        .build()).build()).build();
        return Mono.fromFuture(() -> dynamoDb.transactWriteItems(request))
                .then()
                .onErrorMap(DynamoDbErrors::translate);
    }

    private ScanRequest scanRequest(int limit, String cursor) {
        ScanRequest.Builder builder = ScanRequest.builder().tableName(tables.events()).limit(limit);
        if (cursor != null && !cursor.isBlank()) {
            builder.exclusiveStartKey(EventItemMapper.key(CursorCodec.decode(cursor)));
        }
        return builder.build();
    }

    private static Page<Event> toPage(ScanResponse response) {
        String nextCursor = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? CursorCodec.encode(response.lastEvaluatedKey().get(EventItemMapper.KEY).s())
                : null;
        return new Page<>(response.items().stream().map(EventItemMapper::toDomain).toList(), nextCursor);
    }
}
