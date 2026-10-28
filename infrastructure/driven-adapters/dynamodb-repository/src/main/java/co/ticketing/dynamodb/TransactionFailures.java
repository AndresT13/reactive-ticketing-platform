package co.ticketing.dynamodb;

import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.util.List;
import java.util.Set;

/** Reads the per-item cancellation reasons of a failed {@code TransactWriteItems} call. */
final class TransactionFailures {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Set<String> TRANSIENT_CODES =
            Set.of("TransactionConflict", "ThrottlingError", "ProvisionedThroughputExceeded", "RequestLimitExceeded");

    private final List<String> codes;

    private TransactionFailures(List<String> codes) {
        this.codes = codes;
    }

    static TransactionFailures of(TransactionCanceledException exception) {
        List<String> codes = exception.hasCancellationReasons()
                ? exception.cancellationReasons().stream().map(CancellationReason::code).toList()
                : List.of();
        return new TransactionFailures(codes);
    }

    boolean isTransient() {
        return codes.isEmpty() || codes.stream().anyMatch(TRANSIENT_CODES::contains);
    }

    boolean conditionFailedAt(int index) {
        return index >= 0 && index < codes.size() && CONDITIONAL_CHECK_FAILED.equals(codes.get(index));
    }

    boolean anyConditionFailed() {
        return codes.contains(CONDITIONAL_CHECK_FAILED);
    }
}
