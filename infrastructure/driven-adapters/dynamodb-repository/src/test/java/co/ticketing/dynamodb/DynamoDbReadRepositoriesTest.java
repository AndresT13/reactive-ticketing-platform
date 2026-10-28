package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.EventItemMapper;
import co.ticketing.dynamodb.mapper.InventoryItemMapper;
import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.event.Event;
import co.ticketing.model.event.Venue;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.paginators.QueryPublisher;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static co.ticketing.dynamodb.DynamoDbFixtures.EVENT_ID;
import static co.ticketing.dynamodb.DynamoDbFixtures.NOW;
import static co.ticketing.dynamodb.DynamoDbFixtures.POLICY;
import static co.ticketing.dynamodb.DynamoDbFixtures.TABLES;
import static co.ticketing.dynamodb.DynamoDbFixtures.pending;
import static co.ticketing.dynamodb.DynamoDbFixtures.reserved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamoDbReadRepositoriesTest {

    @Mock private DynamoDbAsyncClient dynamoDb;

    private final OrderItemMapper orderMapper = new OrderItemMapper(POLICY);
    private final Event event = new Event(EVENT_ID, "Rock", new Venue("Arena", "Bogotá"), NOW.plusSeconds(60), 10, NOW);

    @Test
    void findsEventsAndTranslatesThrottling() {
        DynamoDbEventRepository repository = new DynamoDbEventRepository(dynamoDb, TABLES);
        when(dynamoDb.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().item(EventItemMapper.toItem(event)).build()))
                .thenReturn(CompletableFuture.completedFuture(GetItemResponse.builder().build()))
                .thenReturn(CompletableFuture.failedFuture(ProvisionedThroughputExceededException.builder().message("slow down").build()));

        StepVerifier.create(repository.findById(EVENT_ID)).expectNext(event).verifyComplete();
        StepVerifier.create(repository.findById(EVENT_ID)).verifyComplete();
        StepVerifier.create(repository.findById(EVENT_ID)).verifyError(TransientFailureException.class);
    }

    @Test
    void pagesEventsWithAnOpaqueCursor() {
        DynamoDbEventRepository repository = new DynamoDbEventRepository(dynamoDb, TABLES);
        when(dynamoDb.scan(any(ScanRequest.class))).thenReturn(CompletableFuture.completedFuture(ScanResponse.builder()
                .items(List.of(EventItemMapper.toItem(event)))
                .lastEvaluatedKey(EventItemMapper.key(EVENT_ID))
                .build()));

        StepVerifier.create(repository.findAll(10, null))
                .assertNext(page -> {
                    assertThat(page.items()).containsExactly(event);
                    assertThat(page.nextCursor()).isNotBlank();
                })
                .verifyComplete();

        String cursor = CursorCodec.encode(EVENT_ID.value());
        StepVerifier.create(repository.findAll(10, cursor)).expectNextCount(1).verifyComplete();
        ArgumentCaptor<ScanRequest> scans = ArgumentCaptor.forClass(ScanRequest.class);
        verify(dynamoDb, org.mockito.Mockito.times(2)).scan(scans.capture());
        assertThat(scans.getAllValues().get(1).exclusiveStartKey()).isEqualTo(EventItemMapper.key(EVENT_ID));
        assertThat(scans.getAllValues().get(0).limit()).isEqualTo(10);

        when(dynamoDb.scan(any(ScanRequest.class))).thenReturn(CompletableFuture.completedFuture(ScanResponse.builder().items(List.of()).build()));
        StepVerifier.create(repository.findAll(10, null))
                .assertNext(page -> assertThat(page.nextCursor()).isNull())
                .verifyComplete();
        StepVerifier.create(repository.findAll(10, "%%%")).verifyError(DomainValidationException.class);
    }

    @Test
    void createsEventAndInventoryAtomically() {
        DynamoDbEventRepository repository = new DynamoDbEventRepository(dynamoDb, TABLES);
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));

        StepVerifier.create(repository.create(event, Inventory.initial(EVENT_ID, 10))).verifyComplete();

        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(request.capture());
        assertThat(request.getValue().transactItems()).hasSize(2)
                .allSatisfy(item -> assertThat(item.put().conditionExpression()).startsWith("attribute_not_exists"));
    }

    @Test
    void readsInventoryWithStrongConsistency() {
        DynamoDbInventoryRepository repository = new DynamoDbInventoryRepository(dynamoDb, TABLES);
        Inventory inventory = Inventory.initial(EVENT_ID, 10);
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(InventoryItemMapper.toItem(inventory, NOW)).build()));

        StepVerifier.create(repository.findByEventId(EVENT_ID)).expectNext(inventory).verifyComplete();
        ArgumentCaptor<GetItemRequest> request = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(dynamoDb).getItem(request.capture());
        assertThat(request.getValue().consistentRead()).isTrue();
    }

    @Test
    void findsOrdersAndDueOrdersThroughTheSparseIndex() {
        DynamoDbOrderRepository repository = new DynamoDbOrderRepository(dynamoDb, TABLES, orderMapper);
        Order order = reserved(1);
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(orderMapper.toItem(order)).build()));
        when(dynamoDb.query(any(QueryRequest.class))).thenReturn(CompletableFuture.completedFuture(
                QueryResponse.builder().items(List.of(orderMapper.toItem(order), orderMapper.toItem(order))).build()));
        when(dynamoDb.queryPaginator(any(QueryRequest.class)))
                .thenAnswer(invocation -> new QueryPublisher(dynamoDb, invocation.getArgument(0)));

        StepVerifier.create(repository.findById(order.id())).expectNext(order).verifyComplete();
        StepVerifier.create(repository.findDue(OrderStatus.RESERVED, 3, NOW, 1)).expectNext(order).verifyComplete();

        ArgumentCaptor<QueryRequest> query = ArgumentCaptor.forClass(QueryRequest.class);
        verify(dynamoDb).queryPaginator(query.capture());
        assertThat(query.getValue().indexName()).isEqualTo(DynamoDbTables.DEADLINE_INDEX);
        assertThat(query.getValue().expressionAttributeValues().get(":key").s()).isEqualTo("RESERVED#3");
    }

    @Test
    void postponesOnlyStillPendingOrders() {
        DynamoDbOrderRepository repository = new DynamoDbOrderRepository(dynamoDb, TABLES, orderMapper);
        when(dynamoDb.updateItem(any(UpdateItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(UpdateItemResponse.builder().build()))
                .thenReturn(CompletableFuture.failedFuture(ConditionalCheckFailedException.builder().message("moved on").build()));

        StepVerifier.create(repository.postponeDeadline(pending(1).id(), NOW.plusSeconds(120))).verifyComplete();
        StepVerifier.create(repository.postponeDeadline(pending(1).id(), NOW.plusSeconds(120))).verifyComplete();
    }

    @Test
    void readsIdempotencyRecords() {
        DynamoDbIdempotencyStore store = new DynamoDbIdempotencyStore(dynamoDb, TABLES);
        IdempotencyRecord record = new IdempotencyRecord("c#key", "hash", pending(1).id(), NOW.plusSeconds(60));
        when(dynamoDb.getItem(any(GetItemRequest.class))).thenReturn(CompletableFuture.completedFuture(
                GetItemResponse.builder().item(RecordItemMappers.idempotency(record)).build()));

        StepVerifier.create(store.find("c#key")).expectNext(record).verifyComplete();
    }
}
