package co.ticketing.dynamodb;

import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookup;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookupGateway;
import co.ticketing.model.payment.gateways.PaymentGateway;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class DynamoDbSimulatedPaymentGateway
        implements PaymentGateway, PaymentAuthorizationLookupGateway {

    private final DynamoDbAsyncClient dynamoDb;
    private final String tableName;
    private final Duration latency;

    public DynamoDbSimulatedPaymentGateway(
            DynamoDbAsyncClient dynamoDb,
            String tableName,
            Duration latency) {
        this.dynamoDb = Objects.requireNonNull(dynamoDb);
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName is required");
        }
        this.tableName = tableName;
        this.latency = Objects.requireNonNull(latency);
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency cannot be negative");
        }
    }

    @Override
    public Mono<PaymentResult> authorize(PaymentRequest request) {
        Objects.requireNonNull(request);

        return Mono.defer(() -> {
            String orderId = request.orderId().value();
            String token = request.paymentToken();

            if (token.startsWith("tok_unavailable")) {
                return read(orderId)
                        .switchIfEmpty(Mono.error(
                                new TransientFailureException(
                                        "Payment provider unavailable")));
            }

            PaymentResult proposed = token.startsWith("tok_decline")
                    ? new PaymentResult.Declined("CARD_DECLINED")
                    : new PaymentResult.Approved("auth-" + orderId);

            var item = switch (proposed) {
                case PaymentResult.Approved(var id) -> Map.of(
                        "orderId", str(orderId),
                        "status", str("APPROVED"),
                        "authorizationId", str(id));
                case PaymentResult.Declined(var reason) -> Map.of(
                        "orderId", str(orderId),
                        "status", str("DECLINED"),
                        "declineReason", str(reason));
            };

            var put = PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(orderId)")
                    .build();

            Mono<PaymentResult> action =
                    Mono.fromFuture(() -> dynamoDb.putItem(put))
                            .thenReturn(proposed)
                            .onErrorResume(
                                    ConditionalCheckFailedException.class,
                                    error -> read(orderId)
                                            .switchIfEmpty(Mono.error(
                                                    new IllegalStateException(
                                                            "Existing provider result unavailable"))));

            return latency.isZero()
                    ? action
                    : Mono.delay(latency).then(action);
        });
    }

    @Override
    public Mono<PaymentAuthorizationLookup> findByOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return Mono.error(new IllegalArgumentException(
                    "orderId is required"));
        }

        return read(orderId)
                .map(result -> (PaymentAuthorizationLookup) switch (result) {
                    case PaymentResult.Approved(var id) ->
                            new PaymentAuthorizationLookup.Approved(id);
                    case PaymentResult.Declined(var reason) ->
                            new PaymentAuthorizationLookup.Declined(reason);
                })
                .defaultIfEmpty(new PaymentAuthorizationLookup.Unknown());
    }

    private Mono<PaymentResult> read(String orderId) {
        var get = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("orderId", str(orderId)))
                .consistentRead(true)
                .build();

        return Mono.fromFuture(() -> dynamoDb.getItem(get))
                .flatMap(response -> {
                    if (!response.hasItem()
                            || response.item().isEmpty()) {
                        return Mono.empty();
                    }

                    var item = response.item();
                    String status = item.get("status").s();

                    return switch (status) {
                        case "APPROVED" -> Mono.just(
                                new PaymentResult.Approved(
                                        item.get("authorizationId").s()));
                        case "DECLINED" -> Mono.just(
                                new PaymentResult.Declined(
                                        item.get("declineReason").s()));
                        default -> Mono.error(new IllegalStateException(
                                "Invalid provider payment status: " + status));
                    };
                });
    }

    private static AttributeValue str(String value) {
        return AttributeValue.fromS(value);
    }
}
