package co.ticketing.dynamodb;

import co.ticketing.model.common.exception.TransientFailureException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughputExceededException;
import software.amazon.awssdk.services.dynamodb.model.RequestLimitExceededException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;

import java.util.concurrent.CompletionException;

/**
 * Anti-corruption layer for AWS errors: retryable provider failures become
 * {@link TransientFailureException}; anything else is propagated unchanged.
 */
public final class DynamoDbErrors {

    private DynamoDbErrors() {
    }

    public static Throwable translate(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (cause instanceof TransientFailureException) {
            return cause;
        }
        boolean transientFailure = switch (cause) {
            case ProvisionedThroughputExceededException _, RequestLimitExceededException _,
                 TransactionConflictException _ -> true;
            case DynamoDbException dynamo -> dynamo.isThrottlingException() || dynamo.statusCode() >= 500;
            case SdkClientException _ -> true;   // timeouts, connection pool exhaustion, network errors
            default -> false;
        };
        return transientFailure ? new TransientFailureException("DynamoDB temporarily unavailable", cause) : cause;
    }
}
