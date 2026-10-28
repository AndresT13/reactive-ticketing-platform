package co.ticketing.dynamodb;

import co.ticketing.dynamodb.mapper.OrderItemMapper;
import co.ticketing.dynamodb.mapper.RecordItemMappers;
import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.order.AdmissionResult;
import co.ticketing.model.order.CommitResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.OrderTransition;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.RejectionReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static co.ticketing.dynamodb.DynamoDbFixtures.CUSTOMER;
import static co.ticketing.dynamodb.DynamoDbFixtures.EVENT_ID;
import static co.ticketing.dynamodb.DynamoDbFixtures.NOW;
import static co.ticketing.dynamodb.DynamoDbFixtures.POLICY;
import static co.ticketing.dynamodb.DynamoDbFixtures.TABLES;
import static co.ticketing.dynamodb.DynamoDbFixtures.cancelled;
import static co.ticketing.dynamodb.DynamoDbFixtures.pending;
import static co.ticketing.dynamodb.DynamoDbFixtures.reserved;
import static co.ticketing.dynamodb.DynamoDbFixtures.tickets;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DynamoDbWriteStoresTest {

    @Mock private DynamoDbAsyncClient dynamoDb;

    private final OrderItemMapper mapper = new OrderItemMapper(POLICY);

    // ------------------------------------------------------------------ transition store

    @Test
    void reservationIsOneTransactionGuardedByTheInventoryCondition() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        OrderTransition transition = pending(2).reserve(tickets(2), NOW, POLICY);
        succeed();

        StepVerifier.create(store.commit(transition)).expectNext(new CommitResult.Committed(transition)).verifyComplete();

        List<TransactWriteItem> items = capturedItems();
        assertThat(items).hasSize(5);   // inventory + order + 2 tickets + audit
        assertThat(items.get(0).update().conditionExpression()).isEqualTo("#from >= :q");
        assertThat(items.get(0).update().expressionAttributeNames())
                .containsEntry("#from", "available").containsEntry("#to", "reserved");
        assertThat(items.get(0).update().expressionAttributeValues().get(":q").n()).isEqualTo("2");
        assertThat(items.get(1).put().conditionExpression()).isEqualTo("#status = :expectedStatus AND #version = :expectedVersion");
        assertThat(items.get(2).put().conditionExpression()).startsWith("attribute_not_exists");
        assertThat(items.get(4).put().tableName()).isEqualTo("audit");
    }

    @Test
    void failedInventoryConditionMeansInsufficientInventory() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        OrderTransition transition = pending(1).reserve(tickets(1), NOW, POLICY);
        fail("ConditionalCheckFailed", "None", "None", "None");

        StepVerifier.create(store.commit(transition)).expectNext(new CommitResult.InsufficientInventory(transition)).verifyComplete();
    }

    @Test
    void failedOrderConditionMeansStaleOrder() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        OrderTransition transition = pending(1).reserve(tickets(1), NOW, POLICY);
        fail("None", "ConditionalCheckFailed", "None", "None");

        StepVerifier.create(store.commit(transition)).expectNext(new CommitResult.StaleOrder(transition)).verifyComplete();
    }

    @Test
    void transactionConflictsAreTransient() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        fail("TransactionConflict", "None", "None", "None");

        StepVerifier.create(store.commit(pending(1).reserve(tickets(1), NOW, POLICY)))
                .verifyError(TransientFailureException.class);
    }

    @Test
    void unexplainedCancellationsAreTransient() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        fail("None", "ValidationError");

        StepVerifier.create(store.commit(pending(1).reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, NOW)))
                .verifyError(TransientFailureException.class);
    }

    @Test
    void rejectionWithoutTicketsReleasesTheGuardWithoutTouchingInventory() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        OrderTransition transition = pending(1).reject(RejectionReason.SOLD_OUT, Order.WORKER_ACTOR, NOW);
        DynamoDbOrderTransitionStore.Plan plan = store.plan(transition);

        assertThat(plan.inventoryIndex()).isEqualTo(-1);
        assertThat(plan.orderIndex()).isZero();
        assertThat(plan.request().transactItems()).hasSize(3);   // order + audit + guard delete
        assertThat(plan.request().transactItems().get(2).delete().tableName()).isEqualTo("guards");
    }

    @Test
    void expirationMovesTicketsBackWithoutCreationConditions() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        Order reserved = reserved(1);
        DynamoDbOrderTransitionStore.Plan plan = store.plan(reserved.expire(reserved.expiresAt()));

        assertThat(plan.request().transactItems().get(0).update().expressionAttributeNames())
                .containsEntry("#from", "reserved").containsEntry("#to", "available");
        assertThat(plan.request().transactItems().get(2).put().conditionExpression()).isNull();
    }

    @Test
    void complimentaryCreationRequiresANewOrder() {
        DynamoDbOrderTransitionStore store = new DynamoDbOrderTransitionStore(dynamoDb, TABLES, mapper);
        OrderTransition transition = Order.issueComplimentary(new OrderId("cmp-1"), EVENT_ID, new CustomerId("vip"),
                new Quantity(1), tickets(1), new CustomerId("admin"), NOW);

        DynamoDbOrderTransitionStore.Plan plan = store.plan(transition);

        assertThat(plan.request().transactItems().get(1).put().conditionExpression()).isEqualTo("attribute_not_exists(orderId)");
    }

    // ------------------------------------------------------------------ admission store

    @Test
    void admitsOrderIdempotencyRecordGuardAndAudit() {
        DynamoDbOrderAdmissionStore store = admissionStore(key -> Mono.empty());
        Order order = pending(1);
        succeed();

        StepVerifier.create(store.admit(order, record(order))).expectNext(new AdmissionResult.Admitted(order)).verifyComplete();
        assertThat(capturedItems()).hasSize(4)
                .allSatisfy(item -> assertThat(item.put().conditionExpression()).startsWith("attribute_not_exists"));
    }

    @Test
    void concurrentRetryWithTheSameKeyIsReportedAsConflict() {
        Order order = pending(1);
        IdempotencyRecord existing = record(order);
        DynamoDbOrderAdmissionStore store = admissionStore(key -> Mono.just(existing));
        fail("None", "ConditionalCheckFailed", "ConditionalCheckFailed", "None");

        StepVerifier.create(store.admit(order, existing))
                .expectNext(new AdmissionResult.IdempotencyConflict(existing))
                .verifyComplete();
    }

    @Test
    void secondActiveOrderIsRejectedByTheGuard() {
        Order order = pending(1);
        DynamoDbOrderAdmissionStore store = admissionStore(key -> Mono.empty());
        fail("None", "None", "ConditionalCheckFailed", "None");

        StepVerifier.create(store.admit(order, record(order)))
                .expectNext(new AdmissionResult.ActiveOrderExists(EVENT_ID, CUSTOMER))
                .verifyComplete();
    }

    @Test
    void admissionConflictsAreTransient() {
        Order order = pending(1);
        fail("None", "TransactionConflict", "None", "None");
        StepVerifier.create(admissionStore(key -> Mono.empty()).admit(order, record(order)))
                .verifyError(TransientFailureException.class);

        fail("None", "ConditionalCheckFailed", "None", "None");
        StepVerifier.create(admissionStore(key -> Mono.empty()).admit(order, record(order)))
                .verifyError(TransientFailureException.class);
    }

    @Test
    void errorsAreTranslated() {
        assertThat(DynamoDbErrors.translate(new java.util.concurrent.CompletionException(
                software.amazon.awssdk.core.exception.SdkClientException.create("timeout"))))
                .isInstanceOf(TransientFailureException.class);
        assertThat(DynamoDbErrors.translate(software.amazon.awssdk.services.dynamodb.model.DynamoDbException.builder()
                .statusCode(503).message("unavailable").build())).isInstanceOf(TransientFailureException.class);
        assertThat(DynamoDbErrors.translate(software.amazon.awssdk.services.dynamodb.model.DynamoDbException.builder()
                .statusCode(400).message("bad").build())).isNotInstanceOf(TransientFailureException.class);
        IllegalStateException bug = new IllegalStateException("bug");
        assertThat(DynamoDbErrors.translate(bug)).isSameAs(bug);
        TransientFailureException transientFailure = new TransientFailureException("t");
        assertThat(DynamoDbErrors.translate(transientFailure)).isSameAs(transientFailure);
    }

    // ------------------------------------------------------------------ helpers

    private DynamoDbOrderAdmissionStore admissionStore(co.ticketing.model.idempotency.gateways.IdempotencyStore idempotency) {
        return new DynamoDbOrderAdmissionStore(dynamoDb, TABLES, mapper, idempotency);
    }

    private static IdempotencyRecord record(Order order) {
        return new IdempotencyRecord(order.customerId().value() + "#key", "hash", order.id(), NOW.plusSeconds(60));
    }

    private void succeed() {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(TransactWriteItemsResponse.builder().build()));
    }

    private void fail(String... codes) {
        when(dynamoDb.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(cancelled(codes)));
    }

    private List<TransactWriteItem> capturedItems() {
        ArgumentCaptor<TransactWriteItemsRequest> request = ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(dynamoDb).transactWriteItems(request.capture());
        return request.getValue().transactItems();
    }
}
