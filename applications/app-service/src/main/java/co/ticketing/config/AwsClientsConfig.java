package co.ticketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClientBuilder;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Non-blocking AWS clients (Netty NIO). Credentials are never configured here: the SDK default chain
 * resolves environment variables locally and the ECS task role in AWS.
 */
@Configuration(proxyBeanMethods = false)
public class AwsClientsConfig {

    private static final Duration CONNECTION_ACQUISITION_TIMEOUT = Duration.ofSeconds(2);

    @Bean
    DynamoDbAsyncClient dynamoDbAsyncClient(TicketingProperties properties) {
        TicketingProperties.Aws aws = properties.aws();
        DynamoDbAsyncClientBuilder builder = DynamoDbAsyncClient.builder()
                .region(Region.of(aws.region()))
                .httpClientBuilder(httpClient(aws))
                .overrideConfiguration(overrides(aws));
        if (StringUtils.hasText(aws.dynamodbEndpoint())) {
            builder.endpointOverride(URI.create(aws.dynamodbEndpoint()));
        }
        return builder.build();
    }

    @Bean
    SqsAsyncClient sqsAsyncClient(TicketingProperties properties) {
        TicketingProperties.Aws aws = properties.aws();
        SqsAsyncClientBuilder builder = SqsAsyncClient.builder()
                .region(Region.of(aws.region()))
                .httpClientBuilder(httpClient(aws))
                // Long polling holds the call up to 20 s, so SQS uses a longer total timeout than DynamoDB.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .retryPolicy(RetryMode.STANDARD)
                        .build());
        if (StringUtils.hasText(aws.sqsEndpoint())) {
            builder.endpointOverride(URI.create(aws.sqsEndpoint()));
        }
        return builder.build();
    }

    /** The default pool is 50 connections: far too small for a ticket on-sale peak. */
    private static NettyNioAsyncHttpClient.Builder httpClient(TicketingProperties.Aws aws) {
        return NettyNioAsyncHttpClient.builder()
                .maxConcurrency(aws.maxConcurrency())
                .connectionAcquisitionTimeout(CONNECTION_ACQUISITION_TIMEOUT);
    }

    /** Explicit timeouts and a single SDK retry layer (Reactor retries only business-level conflicts). */
    private static ClientOverrideConfiguration overrides(TicketingProperties.Aws aws) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(aws.apiCallTimeout())
                .apiCallAttemptTimeout(aws.apiCallAttemptTimeout())
                .retryPolicy(RetryMode.STANDARD)
                .build();
    }
}
