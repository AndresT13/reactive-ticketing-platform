package co.ticketing.web.error;

/** RFC 9457 Problem Details body, extended with a stable {@code code} and the {@code correlationId}. */
public record ProblemResponse(String type, String title, int status, String detail, String instance, String code,
                              String correlationId) {
}
