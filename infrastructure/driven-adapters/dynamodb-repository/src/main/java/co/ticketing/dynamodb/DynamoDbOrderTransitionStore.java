package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.Attributes;
import co.ticketing.dynamodb.mapper.InventoryItemMapper;
import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.inventory.InventoryMovement;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.gateways.OrderTransitionStore;
import co.ticketing.model.ticket.Ticket;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.Delete;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.ReturnValuesOnConditionCheckFailure;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies an {@link OrderTransition} as one {@code TransactWriteItems} call (Facade + Unit of Work).
 *
 * <p>The anti-oversell barrier is the inventory update: {@code SET from = from - :q, to = to + :q}
 * guarded by {@code from >= :q}. DynamoDB evaluates the condition and the write atomically, so two
 * concurrent workers can never take the same capacity.
 */
public final class DynamoDbOrderTransitionStore implements OrderTransitionStore {

    private final DynamoDbAsyncClient dynamoDb;
    private final DynamoDbTables tables;
    private final OrderItemMapper mapper;

    public DynamoDbOrderTransitionStore(DynamoDbAsyncClient dynamoDb, DynamoDbTables tables, OrderItemMapper mapper) {
        this.dynamoDb = dynamoDb;
        this.tables = tables;
        this.mapper = mapper;
    }

    @Override
    public Mono<CommitResult> commit(OrderTransition transition) {
        return Mono.fromCallable(() -> plan(transition))
                .flatMap(plan -> Mono.fromFuture(() -> dynamoDb.transactWriteItems(plan.request()))
                        .<CommitResult>thenReturn(new CommitResult.Committed(transition))
                        .onErrorResume(TransactionCanceledException.class, error -> classify(error, plan, transition)))
                .onErrorMap(DynamoDbErrors::translate);
    }

    /** Item positions inside the transaction, needed to interpret the cancellation reasons. */
    record Plan(TransactWriteItemsRequest request, int inventoryIndex, int orderIndex) {
    }

    Plan plan(OrderTransition transition) {
        Order after = transition.after();
        Instant now = after.updatedAt();
        List<TransactWriteItem> items = new ArrayList<>();

        int inventoryIndex = -1;
        if (transition.movement() != null) {
            inventoryIndex = items.size();
            items.add(inventoryUpdate(transition.movement(), after, now));
        }

        int orderIndex = items.size();
        items.add(orderPut(transition));

        for (Ticket ticket : transition.affectedTickets()) {
            items.add(ticketPut(ticket, now, transition.createsTickets()));
        }

        items.add(TransactWriteItem.builder().put(Put.builder()
                .tableName(tables.audit())
                .item(RecordItemMappers.audit(transition.audit()))
                .conditionExpression("attribute_not_exists(" + RecordItemMappers.AUDIT_KEY + ")")
                .build()).build());

        if (transition.releasesGuard()) {
            items.add(guardDelete(after));
        }

        return new Plan(TransactWriteItemsRequest.builder().transactItems(items).build(), inventoryIndex, orderIndex);
    }

    private TransactWriteItem inventoryUpdate(InventoryMovement movement, Order order, Instant now) {
        return TransactWriteItem.builder().update(Update.builder()
                .tableName(tables.inventory())
                .key(InventoryItemMapper.key(order.eventId()))
                .updateExpression("SET #from = #from - :q, #to = #to + :q, #version = #version + :one, #updatedAt = :now")
                .conditionExpression("#from >= :q")
                .expressionAttributeNames(Map.of(
                        "#from", InventoryItemMapper.bucketAttribute(movement.from()),
                        "#to", InventoryItemMapper.bucketAttribute(movement.to()),
                        "#version", "version",
                        "#updatedAt", "updatedAt"))
                .expressionAttributeValues(Map.of(
                        ":q", Attributes.n(movement.quantity()),
                        ":one", Attributes.n(1),
                        ":now", Attributes.instant(now)))
                // Returns the current item when the condition fails, without an extra read.
                .returnValuesOnConditionCheckFailure(ReturnValuesOnConditionCheckFailure.ALL_OLD)
                .build()).build();
    }

    private TransactWriteItem orderPut(OrderTransition transition) {
        Put.Builder put = Put.builder().tableName(tables.orders()).item(mapper.toItem(transition.after()));
        Order before = transition.before();
        if (before == null) {
            put.conditionExpression("attribute_not_exists(" + OrderItemMapper.KEY + ")");
        } else {
            // Optimistic locking + state guard: the step applies only to the exact version that was read.
            put.conditionExpression("#status = :expectedStatus AND #version = :expectedVersion")
                    .expressionAttributeNames(Map.of("#status", "status", "#version", "version"))
                    .expressionAttributeValues(Map.of(
                            ":expectedStatus", Attributes.s(before.status().name()),
                            ":expectedVersion", Attributes.n(before.version())));
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    private TransactWriteItem ticketPut(Ticket ticket, Instant now, boolean creation) {
        Put.Builder put = Put.builder().tableName(tables.tickets()).item(RecordItemMappers.ticket(ticket, now));
        if (creation) {
            put.conditionExpression("attribute_not_exists(" + RecordItemMappers.TICKET_KEY + ")");
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    private TransactWriteItem guardDelete(Order order) {
        return TransactWriteItem.builder().delete(Delete.builder()
                .tableName(tables.guards())
                .key(Map.of(RecordItemMappers.GUARD_KEY, Attributes.s(RecordItemMappers.guardKey(order))))
                // Only remove the guard owned by this order; a missing guard is fine.
                .conditionExpression("attribute_not_exists(#guardKey) OR #orderId = :orderId")
                .expressionAttributeNames(Map.of("#guardKey", RecordItemMappers.GUARD_KEY, "#orderId", "orderId"))
                .expressionAttributeValues(Map.of(":orderId", Attributes.s(order.id().value())))
                .build()).build();
    }

    private static Mono<CommitResult> classify(TransactionCanceledException error, Plan plan, OrderTransition transition) {
        TransactionFailures failures = TransactionFailures.of(error);
        if (failures.conditionFailedAt(plan.inventoryIndex())) {
            return Mono.just(new CommitResult.InsufficientInventory(transition));
        }
        if (failures.anyConditionFailed()) {
            return Mono.just(new CommitResult.StaleOrder(transition));
        }
        return Mono.error(new TransientFailureException("Transaction cancelled for order "
                + transition.after().id().value(), error));
    }
}
