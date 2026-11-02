package co.ticketing.dynamodb;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookup;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DynamoDbSimulatedPaymentGatewayTest {

    private static final String TABLE = "payment-provider-test";

    private final DynamoDbAsyncClient client =
            mock(DynamoDbAsyncClient.class);

    private final DynamoDbSimulatedPaymentGateway gateway =
            new DynamoDbSimulatedPaymentGateway(
                    client, TABLE, Duration.ZERO);

    private PaymentRequest request(String token) {
        return new PaymentRequest(
                new OrderId("order-1"),
                new CustomerId("customer-1"),
                new Quantity(1),
                token);
    }

    private static AttributeValue str(String value) {
        return AttributeValue.fromS(value);
    }

    private static GetItemResponse approvedItem() {
        return GetItemResponse.builder()
                .item(Map.of(
                        "orderId", str("order-1"),
                        "status", str("APPROVED"),
                        "authorizationId", str("auth-order-1")))
                .build();
    }

    @Test
    void firstAuthorizationIsStored() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(gateway.authorize(request("tok_visa")))
                .expectNext(new PaymentResult.Approved(
                        "auth-order-1"))
                .verifyComplete();

        verify(client).putItem(argThat((PutItemRequest put) ->
                TABLE.equals(put.tableName())
                && "attribute_not_exists(orderId)"
                        .equals(put.conditionExpression())
                && "APPROVED".equals(
                        put.item().get("status").s())));
    }

    @Test
    void declinedAuthorizationIsStored() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        PutItemResponse.builder().build()));

        StepVerifier.create(
                        gateway.authorize(request("tok_decline_card")))
                .expectNext(new PaymentResult.Declined(
                        "CARD_DECLINED"))
                .verifyComplete();

        verify(client).putItem(argThat((PutItemRequest put) ->
                "DECLINED".equals(
                        put.item().get("status").s())));
    }

    @Test
    void duplicateAuthorizationReadsExistingResult() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        ConditionalCheckFailedException.builder()
                                .message("Already exists")
                                .build()));

        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        approvedItem()));

        StepVerifier.create(
                        gateway.authorize(request("tok_decline_card")))
                .expectNext(new PaymentResult.Approved(
                        "auth-order-1"))
                .verifyComplete();

        verify(client).getItem(argThat((GetItemRequest get) ->
                get.consistentRead()
                && TABLE.equals(get.tableName())));
    }

    @Test
    void lookupReturnsApprovedResult() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        approvedItem()));

        StepVerifier.create(gateway.findByOrderId("order-1"))
                .expectNext(
                        new PaymentAuthorizationLookup.Approved(
                                "auth-order-1"))
                .verifyComplete();

        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void lookupReturnsDeclinedResult() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder()
                                .item(Map.of(
                                        "orderId", str("order-1"),
                                        "status", str("DECLINED"),
                                        "declineReason",
                                        str("CARD_DECLINED")))
                                .build()));

        StepVerifier.create(gateway.findByOrderId("order-1"))
                .expectNext(
                        new PaymentAuthorizationLookup.Declined(
                                "CARD_DECLINED"))
                .verifyComplete();
    }

    @Test
    void missingResultIsUnknown() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().build()));

        StepVerifier.create(gateway.findByOrderId("order-1"))
                .expectNext(new PaymentAuthorizationLookup.Unknown())
                .verifyComplete();
    }

    @Test
    void infrastructureFailureIsNotTreatedAsDecline() {
        when(client.putItem(any(PutItemRequest.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "DynamoDB unavailable")));

        StepVerifier.create(gateway.authorize(request("tok_visa")))
                .expectError(IllegalStateException.class)
                .verify();

        verify(client, never()).getItem(any(GetItemRequest.class));
    }

    @Test
    void providerOutageDoesNotCreateResult() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder().build()));

        StepVerifier.create(
                        gateway.authorize(
                                request("tok_unavailable_demo")))
                .expectError(TransientFailureException.class)
                .verify();

        verify(client).getItem(any(GetItemRequest.class));
        verify(client, never()).putItem(any(PutItemRequest.class));
    }

    @Test
    void invalidProviderStatusIsRejected() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        GetItemResponse.builder()
                                .item(Map.of(
                                        "orderId", str("order-1"),
                                        "status", str("INVALID")))
                                .build()));

        StepVerifier.create(gateway.findByOrderId("order-1"))
                .expectError(IllegalStateException.class)
                .verify();
    }

    @Test
    void validatesConfigurationAndLookupIdentity() {
        assertThatThrownBy(() ->
                new DynamoDbSimulatedPaymentGateway(
                        client, "", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new DynamoDbSimulatedPaymentGateway(
                        client, TABLE, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);

        StepVerifier.create(gateway.findByOrderId(""))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void unavailableProviderRecoversExistingAuthorization() {
        when(client.getItem(any(GetItemRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        approvedItem()));

        StepVerifier.create(gateway.authorize(
                        request("tok_unavailable_retry")))
                .expectNext(new PaymentResult.Approved("auth-order-1"))
                .verifyComplete();

        verify(client, never()).putItem(any(PutItemRequest.class));
    }

}
