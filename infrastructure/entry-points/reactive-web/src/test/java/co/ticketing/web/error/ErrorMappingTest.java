package co.ticketing.web.error;

import co.ticketing.model.common.exception.ActiveOrderExistsException;
import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.IdempotencyKeyReusedException;
import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.ReservationExpiredException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.common.exception.TransientFailureException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMappingTest {

    @Test
    void mapsDomainErrorsToHttpStatuses() {
        assertThat(ErrorMapping.of(new EventNotFoundException("e")).status()).isEqualTo(404);
        assertThat(ErrorMapping.of(new OrderNotFoundException("o")).status()).isEqualTo(404);
        assertThat(ErrorMapping.of(new DomainValidationException("bad")).status()).isEqualTo(400);
        assertThat(ErrorMapping.of(new IdempotencyKeyReusedException()).status()).isEqualTo(422);
        assertThat(ErrorMapping.of(new ActiveOrderExistsException("e")).status()).isEqualTo(409);
        assertThat(ErrorMapping.of(new InvalidStateTransitionException("o", "A", "B")).status()).isEqualTo(409);
        assertThat(ErrorMapping.of(new ReservationExpiredException("o")).status()).isEqualTo(409);
        assertThat(ErrorMapping.of(new SalesClosedException("e")).status()).isEqualTo(409);
        assertThat(ErrorMapping.of(new SoldOutException("e", 1)))
                .extracting(ApiError::status, ApiError::code, ApiError::title)
                .containsExactly(409, "SOLD_OUT", "Sold out");
    }

    @Test
    void mapsTransportAndTechnicalErrors() {
        assertThat(ErrorMapping.of(new RequestValidationException("x")).status()).isEqualTo(400);
        assertThat(ErrorMapping.of(new IdempotencyKeyRequiredException()).status()).isEqualTo(428);
        assertThat(ErrorMapping.of(new ServerWebInputException("bad")).status()).isEqualTo(400);
        assertThat(ErrorMapping.of(new ResponseStatusException(HttpStatus.FORBIDDEN, "no")))
                .extracting(ApiError::status, ApiError::code, ApiError::title)
                .containsExactly(403, "FORBIDDEN", "Forbidden");
        assertThat(ErrorMapping.of(new ResponseStatusException(HttpStatusCode.valueOf(599))).code()).isEqualTo("HTTP_599");
        assertThat(ErrorMapping.of(new TransientFailureException("t")))
                .extracting(ApiError::status, ApiError::retryAfter)
                .containsExactly(503, Duration.ofSeconds(2));
        ApiError unexpected = ErrorMapping.of(new IllegalStateException("secret internals"));
        assertThat(unexpected.status()).isEqualTo(500);
        assertThat(unexpected.detail()).doesNotContain("secret");
    }

    @Test
    void humanizesCodes() {
        assertThat(ErrorMapping.title("ACTIVE_ORDER_EXISTS")).isEqualTo("Active order exists");
    }
}
