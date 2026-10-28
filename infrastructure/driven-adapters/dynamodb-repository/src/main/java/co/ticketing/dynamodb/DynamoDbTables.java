package co.ticketing.dynamodb;

/** Physical table names, provided by configuration (they differ per environment). */
public record DynamoDbTables(String events, String inventory, String orders, String tickets, String audit,
                             String idempotency, String guards) {

    public static final String DEADLINE_INDEX = "deadline-index";
}
