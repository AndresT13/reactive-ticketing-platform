package co.ticketing.model.common.exception;

public final class ReservationExpiredException extends DomainException {

    public ReservationExpiredException(String orderId) {
        super("RESERVATION_EXPIRED", "The reservation of order " + orderId + " has expired");
    }
}
