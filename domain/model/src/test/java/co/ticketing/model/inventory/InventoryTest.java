package co.ticketing.model.inventory;

import co.ticketing.model.common.exception.InvalidStateTransitionException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.event.EventId;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.ticket.TicketStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryTest {

    private static final EventId EVENT = new EventId("evt-1");

    @Test
    void initialInventoryIsFullyAvailable() {
        Inventory inventory = Inventory.initial(EVENT, 100);

        assertThat(inventory.available()).isEqualTo(100);
        assertThat(inventory.held()).isZero();
        assertThat(inventory.canAccommodate(new Quantity(10))).isTrue();
        assertThat(inventory.version()).isZero();
    }

    @Test
    void movementsKeepTheInvariant() {
        Inventory inventory = Inventory.initial(EVENT, 10)
                .apply(new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 4))
                .apply(new InventoryMovement(TicketStatus.RESERVED, TicketStatus.PENDING_CONFIRMATION, 3))
                .apply(new InventoryMovement(TicketStatus.PENDING_CONFIRMATION, TicketStatus.SOLD, 2))
                .apply(new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.COMPLIMENTARY, 1));

        assertThat(inventory.count(TicketStatus.AVAILABLE)).isEqualTo(5);
        assertThat(inventory.count(TicketStatus.RESERVED)).isEqualTo(1);
        assertThat(inventory.count(TicketStatus.PENDING_CONFIRMATION)).isEqualTo(1);
        assertThat(inventory.count(TicketStatus.SOLD)).isEqualTo(2);
        assertThat(inventory.count(TicketStatus.COMPLIMENTARY)).isEqualTo(1);
        assertThat(inventory.held()).isEqualTo(2);
        assertThat(inventory.version()).isEqualTo(4);
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        Inventory inventory = Inventory.initial(EVENT, 2);

        assertThat(inventory.canAccommodate(new Quantity(3))).isFalse();
        assertThatThrownBy(() -> inventory.apply(new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 3)))
                .isInstanceOf(SoldOutException.class);
    }

    @Test
    void cannotMoveTicketsThatAreNotInTheBucket() {
        Inventory inventory = Inventory.initial(EVENT, 2);

        assertThatThrownBy(() -> inventory.apply(new InventoryMovement(TicketStatus.RESERVED, TicketStatus.AVAILABLE, 1)))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void invariantViolationsAreRejected() {
        assertThatThrownBy(() -> new Inventory(EVENT, 10, 5, 0, 0, 0, 0, 0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Inventory(EVENT, 10, 11, -1, 0, 0, 0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void movementValidatesTransitionAndQuantity() {
        assertThatThrownBy(() -> new InventoryMovement(TicketStatus.SOLD, TicketStatus.AVAILABLE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryMovement(TicketStatus.AVAILABLE, TicketStatus.RESERVED, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryMovement(null, TicketStatus.RESERVED, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
