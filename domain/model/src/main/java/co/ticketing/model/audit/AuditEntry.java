package co.ticketing.model.audit;

import java.time.Instant;
import java.util.List;

/**
 * Append-only record of a state transition (non-repudiation). {@code sequence} is the order version
 * after the transition, so the same transition can never be recorded twice.
 */
public record AuditEntry(String aggregateId, long sequence, String fromStatus, String toStatus, int quantity,
                         List<String> ticketIds, String actor, String reason, Instant occurredAt) {

    public AuditEntry {
        ticketIds = ticketIds == null ? List.of() : List.copyOf(ticketIds);
    }
}
