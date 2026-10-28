package co.ticketing.dynamodb;

import co.ticketing.model.common.exception.DomainValidationException;
import co.ticketing.model.event.EventId;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Opaque pagination cursor: the base64url-encoded key of the last item read. */
final class CursorCodec {

    private CursorCodec() {
    }

    static String encode(String eventId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(eventId.getBytes(StandardCharsets.UTF_8));
    }

    static EventId decode(String cursor) {
        try {
            return new EventId(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new DomainValidationException("cursor is invalid");
        }
    }
}
