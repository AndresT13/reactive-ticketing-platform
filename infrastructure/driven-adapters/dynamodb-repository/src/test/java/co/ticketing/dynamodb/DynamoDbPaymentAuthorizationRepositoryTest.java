package co.ticketing.dynamodb;

import co.ticketing.model.payment.authorization.PaymentAuthorization;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDbPaymentAuthorizationRepositoryTest {

    private static final Instant NOW =
            Instant.parse("2026-11-01T20:00:00Z");

    private final DynamoDbAsyncClient client =
            mock(DynamoDbAsyncClient.class);

    private final DynamoDbPaymentAuthorizationRepository repository =
            new DynamoDbPaymentAuthorizationRepository(
                    client, "ticketing-payment-authorizations");

    private final PaymentAuthorization pending =
            PaymentAuthorization.pending("order-123", NOW);

    @Test
    void createsPendingAuthorization() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(repository.createIfAbsent(pending))
                .expectNext(true)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());

        assertThat(captor.getValue().conditionExpression())
                .isEqualTo("attribute_not_exists(orderId)");
        assertThat(captor.getValue().item().get("status").s())
                .isEqualTo("PENDING");
    }

    @Test
    void rejectsDuplicateAuthorization() {
        var failed = new CompletableFuture<PutItemResponse>();
        failed.completeExceptionally(
                ConditionalCheckFailedException.builder()
                        .message("Already exists")
                        .build());

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failed);

        StepVerifier.create(repository.createIfAbsent(pending))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void findsApprovedAuthorization() {
        var item = Map.of(
                "orderId", AttributeValue.fromS("order-123"),
                "status", AttributeValue.fromS("APPROVED"),
                "authorizationId", AttributeValue.fromS("auth-123"),
                "updatedAt", AttributeValue.fromS(NOW.toString()),
                "version", AttributeValue.fromN("1")
        );

        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().item(item).build()));

        StepVerifier.create(repository.findByOrderId("order-123"))
                .assertNext(result -> {
                    assertThat(result.status()).isEqualTo(
                            PaymentAuthorization.Status.APPROVED);
                    assertThat(result.authorizationId())
                            .isEqualTo("auth-123");
                })
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(GetItemRequest.class);
        verify(client).getItem(captor.capture());
        assertThat(captor.getValue().consistentRead()).isTrue();
    }

    @Test
    void returnsEmptyWhenAuthorizationDoesNotExist() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().build()));

        StepVerifier.create(repository.findByOrderId("missing"))
                .verifyComplete();
    }

    @Test
    void updatesUsingPreviousVersion() {
        var approved = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.APPROVED,
                "auth-123",
                null,
                NOW,
                1);

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(repository.updateIfVersionMatches(approved))
                .expectNext(true)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());

        var request = captor.getValue();

        assertThat(request.item().get("version").n())
                .isEqualTo("1");
        assertThat(request.expressionAttributeValues()
                .get(":previous").n()).isEqualTo("0");
        assertThat(request.conditionExpression())
                .contains("attribute_exists(orderId)");
    }

    @Test
    void rejectsStaleVersion() {
        var approved = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.APPROVED,
                "auth-123",
                null,
                NOW,
                1);

        var failed = new CompletableFuture<PutItemResponse>();
        failed.completeExceptionally(
                ConditionalCheckFailedException.builder()
                        .message("Version conflict")
                        .build());

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(failed);

        StepVerifier.create(
                repository.updateIfVersionMatches(approved))
                .expectNext(false)
                .verifyComplete();
    }

    @Test
    void approvedUpdateRequiresNonterminalPreviousStatus() {
        var approved = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.APPROVED,
                "auth-123", null, NOW, 1);

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(repository.updateIfVersionMatches(approved))
                .expectNext(true)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());

        var request = captor.getValue();

        assertThat(request.conditionExpression())
                .contains("#s IN (:pending, :reconciliation)");
        assertThat(request.expressionAttributeValues()
                .get(":previous").n()).isEqualTo("0");
    }

    @Test
    void reconciliationRequiresPendingPreviousStatus() {
        var uncertain = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.NEEDS_RECONCILIATION,
                null, null, NOW, 1);

        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(repository.updateIfVersionMatches(uncertain))
                .expectNext(true)
                .verifyComplete();

        var captor = ArgumentCaptor.forClass(PutItemRequest.class);
        verify(client).putItem(captor.capture());

        assertThat(captor.getValue().conditionExpression())
                .contains("#s = :pending");
    }

    @Test
    void cannotUpdateBackToPending() {
        var invalid = new PaymentAuthorization(
                "order-123",
                PaymentAuthorization.Status.PENDING,
                null, null, NOW, 1);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> repository.updateIfVersionMatches(invalid)
        );

        verify(client, never()).putItem(any(PutItemRequest.class));
    }

}
