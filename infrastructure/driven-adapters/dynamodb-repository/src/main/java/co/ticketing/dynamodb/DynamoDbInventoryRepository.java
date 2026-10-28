package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.InventoryItemMapper;
import co.ticketing.model.event.EventId;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

public final class DynamoDbInventoryRepository implements InventoryRepository {

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;

    public DynamoDbInventoryRepository(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
    }

    @Override
    public Mono<Inventory> findByEventId(EventId eventId) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.inventory())
                .key(InventoryItemMapper.key(eventId))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> InventoryItemMapper.toDomain(response.item()))
                .onErrorMap(DynamoDbErrors::translate);
    }
}
