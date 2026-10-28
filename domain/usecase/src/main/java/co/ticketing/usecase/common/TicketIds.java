package co.ticketing.usecase.common;

import co.ticketing.model.common.IdGenerator;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketId;

import java.util.List;
import java.util.stream.Stream;

public final class TicketIds {

    private TicketIds() {
    }

    public static List<TicketId> generate(IdGenerator ids, Quantity quantity) {
        return Stream.generate(() -> new TicketId(ids.newId())).limit(quantity.value()).toList();
    }
}
