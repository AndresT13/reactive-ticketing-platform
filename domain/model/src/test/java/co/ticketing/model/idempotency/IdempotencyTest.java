package co.ticketing.model.idempotency;

import co.ticketing.model.common.CustomerId;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.order.OrderId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyTest {

    @Test
    void keyFormatIsValidated() {
        assertThat(new IdempotencyKey("3f0a1c2e-uuid").value()).isEqualTo("3f0a1c2e-uuid");
        assertThatThrownBy(() -> new IdempotencyKey("short")).isInstanceOf(DomainValidationException.class);
        assertThatThrownBy(() -> new IdempotencyKey(null)).isInstanceOf(DomainValidationException.class);
    }

    @Test
    void keyIsScopedByCustomer() {
        IdempotencyKey key = new IdempotencyKey("key-12345");

        assertThat(IdempotencyRecord.scopedKey(new CustomerId("c1"), key))
                .isNotEqualTo(IdempotencyRecord.scopedKey(new CustomerId("c2"), key));
    }

    @Test
    void recordMatchesFingerprintAndExpires() {
        Instant now = Instant.parse("2026-10-27T20:00:00Z");
        IdempotencyRecord record = new IdempotencyRecord("c1#key", RequestFingerprint.of("evt", 2), new OrderId("o"), now.plusSeconds(60));

        assertThat(record.matches(RequestFingerprint.of("evt", 2))).isTrue();
        assertThat(record.matches(RequestFingerprint.of("evt", 3))).isFalse();
        assertThat(record.isExpiredAt(now)).isFalse();
        assertThat(record.isExpiredAt(now.plusSeconds(60))).isTrue();
    }

    @Test
    void fingerprintIsADeterministicSha256() {
        assertThat(RequestFingerprint.of("a", 1)).hasSize(64).isEqualTo(RequestFingerprint.of("a", 1));
    }
}
