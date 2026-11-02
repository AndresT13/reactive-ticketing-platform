package co.ticketing.dynamodb;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.order.OrderId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.payment.PaymentRequest;
import co.ticketing.model.payment.PaymentResult;
import co.ticketing.model.payment.authorization.PaymentAuthorizationLookup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DurablePaymentIntegrationTest {

    @Test
    @EnabledIfEnvironmentVariable(
            named = "DDB_INTEGRATION_URL", matches = ".+")
    void concurrentWorkersReuseDurableProviderResult() {

        String endpoint = System.getenv("DDB_INTEGRATION_URL");
        String table = "test-provider-" +
                UUID.randomUUID().toString().replace("-", "");

        try (var client = DynamoDbAsyncClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build()) {

            client.createTable(CreateTableRequest.builder()
                    .tableName(table)
                    .attributeDefinitions(AttributeDefinition.builder()
                            .attributeName("orderId")
                            .attributeType(ScalarAttributeType.S)
                            .build())
                    .keySchema(KeySchemaElement.builder()
                            .attributeName("orderId")
                            .keyType(KeyType.HASH)
                            .build())
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build()).join();

            try {
                var worker1 = new DynamoDbSimulatedPaymentGateway(
                        client, table, Duration.ZERO);

                var worker2 = new DynamoDbSimulatedPaymentGateway(
                        client, table, Duration.ZERO);

                String orderId = "integration-" + UUID.randomUUID();

                var request = new PaymentRequest(
                        new OrderId(orderId),
                        new CustomerId("customer-integration"),
                        new Quantity(1),
                        "tok_visa");

                List<PaymentResult> results = Flux.range(0, 20)
                        .flatMap(i -> (i % 2 == 0 ? worker1 : worker2)
                                .authorize(request)
                                .subscribeOn(Schedulers.parallel()), 20)
                        .collectList()
                        .block(Duration.ofSeconds(30));

                assertThat(results).hasSize(20);
                assertThat(results).allMatch(result ->
                        result.equals(new PaymentResult.Approved(
                                "auth-" + orderId)));

                var restartedWorker = new DynamoDbSimulatedPaymentGateway(
                        client, table, Duration.ZERO);

                PaymentAuthorizationLookup lookup = restartedWorker
                        .findByOrderId(orderId)
                        .block(Duration.ofSeconds(10));

                assertThat(lookup).isEqualTo(
                        new PaymentAuthorizationLookup.Approved(
                                "auth-" + orderId));

                PaymentResult repeated = restartedWorker
                        .authorize(new PaymentRequest(
                                new OrderId(orderId),
                                new CustomerId("customer-integration"),
                                new Quantity(1),
                                "tok_decline_card"))
                        .block(Duration.ofSeconds(10));

                assertThat(repeated).isEqualTo(
                        new PaymentResult.Approved(
                                "auth-" + orderId));

                var scan = client.scan(ScanRequest.builder()
                        .tableName(table)
                        .consistentRead(true)
                        .build()).join();

                assertThat(scan.items()).hasSize(1);

            } finally {
                client.deleteTable(DeleteTableRequest.builder()
                        .tableName(table)
                        .build()).join();
            }
        }
    }
}
