package co.ticketing.sqs.listener;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.usecase.order.ReservationOutcome;
import co.ticketing.usecase.order.ReserveTicketsUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.util.Locale;

/** Asynchronous adapter for the reservation step. */
public final class ReservationRequestListener extends AbstractSqsListener {

    private final ReserveTicketsUseCase reserveTickets;

    public ReservationRequestListener(SqsAsyncClient sqs, String queueUrl, ListenerSettings settings, CommandMessageCodec codec,
                                      MeterRegistry meters, ReserveTicketsUseCase reserveTickets) {
        super(sqs, "reservation-requests", queueUrl, settings, codec, meters);
        this.reserveTickets = reserveTickets;
    }

    @Override
    protected Mono<String> handle(OrderCommand command) {
        if (!(command instanceof ReserveTicketsCommand reserve)) {
            return Mono.error(new IllegalArgumentException("Unexpected command on reservation queue: " + command));
        }
        return reserveTickets.execute(reserve.orderId()).map(ReservationRequestListener::label);
    }

    static String label(ReservationOutcome outcome) {
        return switch (outcome) {
            case ReservationOutcome.Reserved _ -> "reserved";
            case ReservationOutcome.Rejected(_, var reason) -> "rejected_" + reason.name().toLowerCase(Locale.ROOT);
            case ReservationOutcome.AlreadyProcessed _ -> "duplicate";
        };
    }
}
