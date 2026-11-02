package co.ticketing.dynamodb;

import co.ticketing.model.payment.compensation.CompensationStatus;
import co.ticketing.model.payment.compensation.PaymentCompensation;
import co.ticketing.model.payment.compensation.PaymentCompensationRepository;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import java.util.Objects;

import java.time.Instant;
import java.util.Map;

public final class DynamoDbPaymentCompensationRepository
        implements PaymentCompensationRepository {

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;

    public DynamoDbPaymentCompensationRepository(
            DynamoDbAsyncClient dynamoDb,
            String tableName
    ) {
        this.dynamoDb = java.util.Objects.requireNonNull(dynamoDb);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
        this.tableName = tableName;
    }

    @Override
    public Mono<Boolean> createIfAbsent(PaymentCompensation compensation) {
        var request = PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(compensation))
                .conditionExpression("attribute_not_exists(compensationId)")
                .build();

        return Mono.fromFuture(() -> dynamoDb.putItem(request))
                .thenReturn(true)
                .onErrorResume(
                        ConditionalCheckFailedException.class,
                        error -> Mono.just(false)
                );
    }

    @Override
    public Mono<PaymentCompensation> findById(String compensationId) {
        var request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("compensationId", s(compensationId)))
                .consistentRead(true)
                .build();

        return Mono.fromFuture(() -> dynamoDb.getItem(request))
                .flatMap(response -> response.hasItem()
                        ? Mono.just(fromItem(response.item()))
                        : Mono.empty());
    }


    @Override
    public Mono<Boolean> updateIfVersionMatches(
            PaymentCompensation expected,
            PaymentCompensation updated
    ) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(updated);

        if (!expected.compensationId().equals(updated.compensationId())
                || !expected.orderId().equals(updated.orderId())
                || !expected.authorizationId().equals(updated.authorizationId())
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Invalid compensation transition");
        }

        var request = PutItemRequest.builder()
                .tableName(tableName)
                .item(toItem(updated))
                .conditionExpression("#version = :version AND #status = :status")
                .expressionAttributeNames(Map.of(
                        "#version", "version",
                        "#status", "status"
                ))
                .expressionAttributeValues(Map.of(
                        ":version", nLong(expected.version()),
                        ":status", s(expected.status().name())
                ))
                .build();

        return Mono.fromFuture(() -> dynamoDb.putItem(request))
                .thenReturn(true)
                .onErrorResume(
                        ConditionalCheckFailedException.class,
                        error -> Mono.just(false)
                );
    }

    private static AttributeValue nLong(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static Map<String, AttributeValue> toItem(
            PaymentCompensation compensation
    ) {
        return Map.of(
                "compensationId", s(compensation.compensationId()),
                "orderId", s(compensation.orderId()),
                "authorizationId", s(compensation.authorizationId()),
                "status", s(compensation.status().name()),
                "attempts", n(compensation.attempts()),
                "nextAttemptAt", s(compensation.nextAttemptAt().toString()),
                "updatedAt", s(compensation.updatedAt().toString()),
                "version", nLong(compensation.version())
        );
    }

    private static PaymentCompensation fromItem(
            Map<String, AttributeValue> item
    ) {
        return new PaymentCompensation(
                item.get("compensationId").s(),
                item.get("orderId").s(),
                item.get("authorizationId").s(),
                CompensationStatus.valueOf(item.get("status").s()),
                Integer.parseInt(item.get("attempts").n()),
                Instant.parse(item.get("nextAttemptAt").s()),
                Instant.parse(item.get("updatedAt").s()),
                Long.parseLong(item.get("version").n())
        );
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }
}
