package co.ticketing.dynamodb;

import co.ticketing.model.payment.authorization.PaymentAuthorization;
import co.ticketing.model.payment.authorization.PaymentAuthorizationRepository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class DynamoDbPaymentAuthorizationRepository
        implements PaymentAuthorizationRepository {

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;

    public DynamoDbPaymentAuthorizationRepository(
            DynamoDbAsyncClient dynamoDb, String tableName) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
        this.tableName = tableName;
    }

    @Override
    public Mono<PaymentAuthorization> findByOrderId(String orderId) {
        var request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("orderId", s(orderId)))
                .consistentRead(true)
                .build();

        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .flatMap(response -> response.hasItem()
                        ? Mono.just(fromItem(response.item()))
                        : Mono.empty());
    }

    @Override
    public Mono<Boolean> createIfAbsent(
            PaymentAuthorization authorization) {
        Objects.requireNonNull(authorization);
        if (authorization.version() != 0
                || authorization.status() != PaymentAuthorization.Status.PENDING) {
            return Mono.error(new IllegalArgumentException(
                    "Initial authorization must be PENDING at version 0"));
        }

        var request = PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(authorization))
                .conditionExpression("attribute_not_exists(orderId)")
                .build();

        return Mono.fromFuture(() -> dynamoDb.putItem(request))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class,
                        error -> Mono.just(false));
    }

    @Override
    public Mono<Boolean> updateIfVersionMatches(
            PaymentAuthorization authorization) {
        Objects.requireNonNull(authorization);
        if (authorization.version() < 1) {
            return Mono.error(new IllegalArgumentException(
                    "Updated authorization version must be positive"));
        }

        var status = authorization.status();

        String condition = switch (status) {
            case APPROVED, DECLINED ->
                    "attribute_exists(orderId) AND #v = :previous "
                    + "AND #s IN (:pending, :reconciliation)";
            case NEEDS_RECONCILIATION ->
                    "attribute_exists(orderId) AND #v = :previous "
                    + "AND #s = :pending";
            case PENDING -> throw new IllegalArgumentException(
                    "Cannot update an authorization back to PENDING");
        };

        var request = PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(authorization))
                .conditionExpression(condition)
                .expressionAttributeNames(Map.of(
                        "#v", "version",
                        "#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":previous", n(authorization.version() - 1),
                        ":pending", s("PENDING"),
                        ":reconciliation", s("NEEDS_RECONCILIATION")))
                .build();

        return Mono.fromFuture(() -> dynamoDb.putItem(request))
                .thenReturn(true)
                .onErrorResume(ConditionalCheckFailedException.class,
                        error -> Mono.just(false));
    }

    private static Map<String, AttributeValue> toItem(
            PaymentAuthorization a) {
        var item = new HashMap<String, AttributeValue>();
        item.put("orderId", s(a.orderId()));
        item.put("status", s(a.status().name()));
        item.put("updatedAt", s(a.updatedAt().toString()));
        item.put("version", n(a.version()));

        if (a.authorizationId() != null) {
            item.put("authorizationId", s(a.authorizationId()));
        }
        if (a.declineReason() != null) {
            item.put("declineReason", s(a.declineReason()));
        }
        return item;
    }

    private static PaymentAuthorization fromItem(
            Map<String, AttributeValue> item) {
        return new PaymentAuthorization(
                item.get("orderId").s(),
                PaymentAuthorization.Status.valueOf(
                        item.get("status").s()),
                item.containsKey("authorizationId")
                        ? item.get("authorizationId").s() : null,
                item.containsKey("declineReason")
                        ? item.get("declineReason").s() : null,
                Instant.parse(item.get("updatedAt").s()),
                Long.parseLong(item.get("version").n()));
    }

    private static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    private static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }
}
