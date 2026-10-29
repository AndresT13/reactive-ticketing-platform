package co.ticketing.model.event;

import co.ticketing.model.common.exception.DomainValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventTest {

    private static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");
    private static final Venue VENUE = new Venue("Movistar Arena", "Bogotá");

    @Test
    void createsAFutureEvent() {
        Event event = Event.create(new EventId("evt-1"), "  Rock Fest  ", VENUE, NOW.plusSeconds(3600), 500, NOW);

        assertThat(event.name()).isEqualTo("Rock Fest");
        assertThat(event.createdAt()).isEqualTo(NOW);
        assertThat(event.isOnSaleAt(NOW)).isTrue();
        assertThat(event.isOnSaleAt(NOW.plusSeconds(3600))).isFalse();
    }

    @Test
    void rejectsPastDatesAndInvalidCapacity() {
        EventId id = new EventId("evt-1");

        assertThatThrownBy(() -> Event.create(id, "n", VENUE, NOW, 10, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(id, "n", VENUE, null, 10, NOW)).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(id, "n", VENUE, NOW.plusSeconds(1), 0, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(id, "n", VENUE, NOW.plusSeconds(1), Event.MAX_CAPACITY + 1, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> Event.create(id, " ", VENUE, NOW.plusSeconds(1), 10, NOW))
                .isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new Venue("", "Bogotá")).isInstanceOf(DomainValidationException.class);
    }
}
