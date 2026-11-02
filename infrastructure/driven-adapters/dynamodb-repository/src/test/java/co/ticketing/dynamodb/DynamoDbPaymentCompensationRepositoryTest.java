package co.ticketing.dynamodb;

import co.ticketing.model.payment.compensation.PaymentCompensation;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDbPaymentCompensationRepositoryTest {

    private final DynamoDbAsyncClient client =
            mock(DynamoDbAsyncClient.class);

    private final DynamoDbPaymentCompensationRepository repository =
            new DynamoDbPaymentCompensationRepository(client, "ticketing-payment-compensations");

    private final PaymentCompensation compensation =
            PaymentCompensation.pending(
                    "order-123",
                    "auth-123",
                    Instant.parse("2026-11-01T12:00:00Z")
            );

    @Test
    void createsCompensationWhenAbsent() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()
                ));

        StepVerifier.create(repository.createIfAbsent(compensation))
                .expectNext(true)
                .verifyComplete();

        verify(client).putItem(any(PutItemRequest.class));
    }

    @Test
    void returnsFalseForDuplicateCompensation() {
        var future = new CompletableFuture<PutItemResponse>();
        future.completeExceptionally(
                ConditionalCheckFailedException.builder()
                        .message("Already exists")
                        .build()
        );

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(future);

        StepVerifier.create(repository.createIfAbsent(compensation))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void findsExistingCompensation() {
        Map<String, AttributeValue> item = Map.of(
                "compensationId", AttributeValue.fromS("auth-123"),
                "orderId", AttributeValue.fromS("order-123"),
                "authorizationId", AttributeValue.fromS("auth-123"),
                "status", AttributeValue.fromS("PENDING"),
                "attempts", AttributeValue.fromN("0"),
                "version", AttributeValue.fromN("0"),
                "nextAttemptAt",
                    AttributeValue.fromS("2026-11-01T12:00:00Z"),
                "updatedAt",
                    AttributeValue.fromS("2026-11-01T12:00:00Z")
        );

        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()
                ));

        StepVerifier.create(repository.findById("auth-123"))
                .expectNext(compensation)
                .verifyComplete();
    }

    @Test
    void returnsEmptyWhenNotFound() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().build()
                ));

        StepVerifier.create(repository.findById("missing"))
                .verifyComplete();
    }

    @Test
    void updatesWithExpectedVersion() {
        var updated = compensation.startProcessing(
                compensation.updatedAt()
        );

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()
                ));

        StepVerifier.create(
                repository.updateIfVersionMatches(compensation, updated)
        )
                .expectNext(true)
                .verifyComplete();

        var captor =
                org.mockito.ArgumentCaptor.forClass(PutItemRequest.class);

        verify(client).putItem(captor.capture());

        var request = captor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals(
                "1",
                request.item().get("version").n()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "0",
                request.expressionAttributeValues().get(":version").n()
        );

        org.junit.jupiter.api.Assertions.assertEquals(
                "PENDING",
                request.expressionAttributeValues().get(":status").s()
        );
    }

    @Test
    void rejectsStaleVersion() {
        var updated = compensation.startProcessing(
                compensation.updatedAt()
        );

        var failure = new CompletableFuture<PutItemResponse>();
        failure.completeExceptionally(
                ConditionalCheckFailedException.builder()
                        .message("Version conflict")
                        .build()
        );

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failure);

        StepVerifier.create(
                repository.updateIfVersionMatches(compensation, updated)
        )
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void rejectsTransitionWithDifferentIdentity() {
        var different = PaymentCompensation.pending(
                "another-order",
                "another-authorization",
                compensation.updatedAt()
        ).startProcessing(compensation.updatedAt());

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> repository.updateIfVersionMatches(
                        compensation,
                        different
                )
        );

        verify(client, never()).putItem(any(PutItemRequest.class));
    }

}
