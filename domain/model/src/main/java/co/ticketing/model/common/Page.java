package co.ticketing.model.common;

import java.util.List;
import java.util.Optional;

/** A page of results plus an opaque cursor to request the next one. */
public record Page<T>(List<T> items, String nextCursor) {

    public Page {
        items = List.copyOf(items);
    }

    public Optional<String> next() {
        return Optional.ofNullable(nextCursor);
    }
}
