package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.idempotency.gateways.IdempotencyStore;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

public final class DynamoDbIdempotencyStore implements IdempotencyStore {

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;

    public DynamoDbIdempotencyStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
    }

    @Override
    public Mono<IdempotencyRecord> find(String scopedKey) {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tables.idempotency())
                .key(RecordItemMappers.idempotencyKey(scopedKey))
                .consistentRead(true)
                .build();
        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .filter(GetItemResponse::hasItem)
                .map(response -> RecordItemMappers.toIdempotency(response.item()))
                .onErrorMap(DynamoDbErrors::translate);
    }
}
