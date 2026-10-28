package co.ticketing.model.common;

/** Port that generates unique identifiers. A functional interface so tests can use a lambda. */
@FunctionalInterface
public interface IdGenerator {

    String newId();
}
