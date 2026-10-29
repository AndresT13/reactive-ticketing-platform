package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.audit.AuditEntry;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.idempotency.gateways.IdempotencyStore;
import co.ticketing.model.order.AdmissionResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.gateways.OrderAdmissionStore;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;
import java.util.Map;

/**
 * Admits a PENDING order in a single transaction:
 * <ol start="0">
 *   <li>order (must not exist)</li>
 *   <li>idempotency record (must not exist: a concurrent retry with the same key loses here)</li>
 *   <li>active-order guard for event + customer (must not exist: anti-hoarding rule)</li>
 *   <li>audit entry of the creation</li>
 * </ol>
 */
public final class DynamoDbOrderAdmissionStore implements OrderAdmissionStore {

    private static final int IDEMPOTENCY_INDEX = 1;
    private static final int GUARD_INDEX = 2;

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;
    private final OrderItemMapper mapper;
    private final IdempotencyStore idempotency;

    public DynamoDbOrderAdmissionStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables, OrderItemMapper mapper,
                                       IdempotencyStore idempotency) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
        this.mapper = mapper;
        this.idempotency = idempotency;
    }

    @Override
    public Mono<AdmissionResult> admit(Order order, IdempotencyRecord idempotencyRecord) {
        return Mono.fromFuture(() -> dynamoDb.transactWriteItems(request(order, idempotencyRecord)))
                .<AdmissionResult>thenReturn(new AdmissionResult.Admitted(order))
                .onErrorResume(TransactionCanceledException.class, error -> classify(error, order, idempotencyRecord))
                .onErrorMap(DynamoDbErrors::translate);
    }

    private Mono<AdmissionResult> classify(TransactionCanceledException error, Order order, IdempotencyRecord record) {
        TransactionFailures failures = TransactionFailures.of(error);
        if (failures.conditionFailedAt(IDEMPOTENCY_INDEX)) {
            return idempotency.find(record.scopedKey())
                    .<AdmissionResult>map(AdmissionResult.IdempotencyConflict::new)
                    .switchIfEmpty(Mono.error(() -> new TransientFailureException("Idempotency record vanished; retry")));
        }
        if (failures.conditionFailedAt(GUARD_INDEX)) {
            return Mono.just(new AdmissionResult.ActiveOrderExists(order.eventId(), order.customerId()));
        }
        return Mono.error(new TransientFailureException("Order admission was cancelled; retry", error));
    }

    private TransactWriteItemsRequest request(Order order, IdempotencyRecord record) {
        AuditEntry audit = new AuditEntry(order.id().value(), order.version(), null, order.status().name(), 0,
                List.of(), "customer:" + order.customerId().value(), "ORDER_PLACED", order.createdAt());
        return TransactWriteItemsRequest.builder().transactItems(
                put(tables.orders(), mapper.toItem(order), OrderItemMapper.KEY),
                put(tables.idempotency(), RecordItemMappers.idempotency(record), RecordItemMappers.IDEMPOTENCY_KEY),
                put(tables.guards(), RecordItemMappers.guard(order), RecordItemMappers.GUARD_KEY),
                put(tables.audit(), RecordItemMappers.audit(audit), RecordItemMappers.AUDIT_KEY)
        ).build();
    }

    private static TransactWriteItem put(String table, Map<String, AttributeValue> item, String keyAttribute) {
        return TransactWriteItem.builder().put(Put.builder()
                .tableName(table)
                .item(item)
                .conditionExpression("attribute_not_exists(" + keyAttribute + ")")
                .build()).build();
    }
}
