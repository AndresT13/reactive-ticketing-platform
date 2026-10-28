package co.ticketing.dynamodb.mapper;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Helpers to read and write DynamoDB attribute values. Instants are stored as epoch milliseconds. */
public final class Attributes {

    private Attributes() {
    }

    public static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    public static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    public static AttributeValue instant(Instant value) {
        return n(value.toEpochMilli());
    }

    public static AttributeValue stringList(List<String> values) {
        return AttributeValue.fromL(values.stream().map(AttributeValue::fromS).toList());
    }

    public static String string(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    public static long number(Map<String, AttributeValue> item, String name) {
        return Long.parseLong(item.get(name).n());
    }

    public static int integer(Map<String, AttributeValue> item, String name) {
        return Integer.parseInt(item.get(name).n());
    }

    public static Instant instant(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        return value == null || value.n() == null ? null : Instant.ofEpochMilli(Long.parseLong(value.n()));
    }

    public static List<String> stringList(Map<String, AttributeValue> item, String name) {
        AttributeValue value = item.get(name);
        if (value == null || !value.hasL()) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}
