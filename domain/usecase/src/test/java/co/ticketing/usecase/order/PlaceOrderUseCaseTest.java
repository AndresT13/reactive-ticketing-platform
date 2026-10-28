package co.ticketing.usecase.order;

import co.ticketing.model.common.exception.ActiveOrderExistsException;
import co.ticketing.model.common.exception.EventNotFoundException;
import co.ticketing.model.common.exception.IdempotencyKeyReusedException;
import co.ticketing.model.common.exception.OrderNotFoundException;
import co.ticketing.model.common.exception.SalesClosedException;
import co.ticketing.model.common.exception.SoldOutException;
import co.ticketing.model.common.exception.TransientFailureException;
import co.ticketing.model.event.gateways.EventRepository;
import co.ticketing.model.idempotency.IdempotencyKey;
import co.ticketing.model.idempotency.IdempotencyRecord;
import co.ticketing.model.idempotency.RequestFingerprint;
import co.ticketing.model.idempotency.gateways.IdempotencyStore;
import co.ticketing.model.inventory.Inventory;
import co.ticketing.model.inventory.gateways.InventoryRepository;
import co.ticketing.model.order.AdmissionResult;
import co.ticketing.model.order.Order;
import co.ticketing.model.order.OrderCommand;
import co.ticketing.model.order.OrderStatus;
import co.ticketing.model.order.Quantity;
import co.ticketing.model.order.ReserveTicketsCommand;
import co.ticketing.model.order.gateways.OrderAdmissionStore;
import co.ticketing.model.order.gateways.OrderCommandPublisher;
import co.ticketing.model.order.gateways.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static co.ticketing.usecase.UseCaseFixtures.CLOCK;
import static co.ticketing.usecase.UseCaseFixtures.CUSTOMER;
import static co.ticketing.usecase.UseCaseFixtures.EVENT_ID;
import static co.ticketing.usecase.UseCaseFixtures.NOW;
import static co.ticketing.usecase.UseCaseFixtures.POLICY;
import static co.ticketing.usecase.UseCaseFixtures.event;
import static co.ticketing.usecase.UseCaseFixtures.pastEvent;
import static co.ticketing.usecase.UseCaseFixtures.pending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlaceOrderUseCaseTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("key-0001");
    private static final String SCOPED_KEY = IdempotencyRecord.scopedKey(CUSTOMER, KEY);
    private static final String FINGERPRINT = RequestFingerprint.of(EVENT_ID.value(), 2);

    @Mock private EventRepository events;
    @Mock private InventoryRepository inventories;
    @Mock private IdempotencyStore idempotency;
    @Mock private OrderAdmissionStore admissions;
    @Mock private OrderRepository orders;
    @Mock private OrderCommandPublisher publisher;

    private PlaceOrderUseCase useCase;
    private final PlaceOrderCommand command = new PlaceOrderCommand(CUSTOMER, EVENT_ID, new Quantity(2), KEY);

    @BeforeEach
    void setUp() {
        useCase = new PlaceOrderUseCase(events, inventories, idempotency, admissions, orders, publisher,
                () -> "ord-1", CLOCK, POLICY);
        when(idempotency.find(SCOPED_KEY)).thenReturn(Mono.empty());
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(event()));
        when(inventories.findByEventId(EVENT_ID)).thenReturn(Mono.just(Inventory.initial(EVENT_ID, 100)));
        when(admissions.admit(any(), any())).thenAnswer(invocation ->
                Mono.just(new AdmissionResult.Admitted(invocation.getArgument(0))));
        when(publisher.publish(any())).thenReturn(Mono.empty());
    }

    @Test
    void admitsPendingOrderAndPublishesReservationCommand() {
        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.order().status()).isEqualTo(OrderStatus.PENDING);
                    assertThat(result.order().customerId()).isEqualTo(CUSTOMER);
                    assertThat(result.replayed()).isFalse();
                    assertThat(result.dispatched()).isTrue();
                })
                .verifyComplete();

        ArgumentCaptor<OrderCommand> published = ArgumentCaptor.forClass(OrderCommand.class);
        verify(publisher).publish(published.capture());
        assertThat(published.getValue()).isInstanceOf(ReserveTicketsCommand.class);
        ArgumentCaptor<IdempotencyRecord> record = ArgumentCaptor.forClass(IdempotencyRecord.class);
        verify(admissions).admit(any(), record.capture());
        assertThat(record.getValue().scopedKey()).isEqualTo(SCOPED_KEY);
        assertThat(record.getValue().requestHash()).isEqualTo(FINGERPRINT);
        assertThat(record.getValue().expiresAt()).isEqualTo(NOW.plus(POLICY.idempotencyTtl()));
    }

    @Test
    void stillAcceptsTheOrderWhenPublishingFails() {
        when(publisher.publish(any())).thenReturn(Mono.error(new TransientFailureException("sqs down")));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> assertThat(result.dispatched()).isFalse())
                .verifyComplete();
    }

    @Test
    void replaysTheOriginalOrderForTheSameKeyAndPayload() {
        Order original = pending(2);
        when(idempotency.find(SCOPED_KEY)).thenReturn(Mono.just(
                new IdempotencyRecord(SCOPED_KEY, FINGERPRINT, original.id(), NOW.plusSeconds(60))));
        when(orders.findById(original.id())).thenReturn(Mono.just(original));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> {
                    assertThat(result.replayed()).isTrue();
                    assertThat(result.order()).isEqualTo(original);
                })
                .verifyComplete();
        verify(admissions, never()).admit(any(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void rejectsAKeyReusedWithADifferentPayload() {
        when(idempotency.find(SCOPED_KEY)).thenReturn(Mono.just(
                new IdempotencyRecord(SCOPED_KEY, "other-hash", pending(2).id(), NOW.plusSeconds(60))));

        StepVerifier.create(useCase.execute(command)).verifyError(IdempotencyKeyReusedException.class);
    }

    @Test
    void replayFailsWhenTheOriginalOrderIsMissing() {
        when(idempotency.find(SCOPED_KEY)).thenReturn(Mono.just(
                new IdempotencyRecord(SCOPED_KEY, FINGERPRINT, pending(2).id(), NOW.plusSeconds(60))));
        when(orders.findById(any())).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command)).verifyError(OrderNotFoundException.class);
    }

    @Test
    void ignoresAnExpiredIdempotencyRecord() {
        when(idempotency.find(SCOPED_KEY)).thenReturn(Mono.just(
                new IdempotencyRecord(SCOPED_KEY, FINGERPRINT, pending(2).id(), NOW.minusSeconds(1))));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> assertThat(result.replayed()).isFalse())
                .verifyComplete();
    }

    @Test
    void failsFastWhenTheEventDoesNotExist() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command)).verifyError(EventNotFoundException.class);
    }

    @Test
    void failsWhenTheInventoryDoesNotExist() {
        when(inventories.findByEventId(EVENT_ID)).thenReturn(Mono.empty());

        StepVerifier.create(useCase.execute(command)).verifyError(EventNotFoundException.class);
    }

    @Test
    void rejectsPurchasesForPastEvents() {
        when(events.findById(EVENT_ID)).thenReturn(Mono.just(pastEvent()));

        StepVerifier.create(useCase.execute(command)).verifyError(SalesClosedException.class);
    }

    @Test
    void failsFastWithoutEnqueueingWhenSoldOut() {
        when(inventories.findByEventId(EVENT_ID)).thenReturn(Mono.just(new Inventory(EVENT_ID, 100, 1, 0, 0, 99, 0, 9)));

        StepVerifier.create(useCase.execute(command)).verifyError(SoldOutException.class);
        verify(admissions, never()).admit(any(), any());
        verify(publisher, never()).publish(any());
    }

    @Test
    void rejectsASecondActiveOrderForTheSameEvent() {
        when(admissions.admit(any(), any())).thenReturn(Mono.just(new AdmissionResult.ActiveOrderExists(EVENT_ID, CUSTOMER)));

        StepVerifier.create(useCase.execute(command)).verifyError(ActiveOrderExistsException.class);
    }

    @Test
    void concurrentRetryWithTheSameKeyIsReplayed() {
        Order original = pending(2);
        when(admissions.admit(any(), any())).thenReturn(Mono.just(new AdmissionResult.IdempotencyConflict(
                new IdempotencyRecord(SCOPED_KEY, FINGERPRINT, original.id(), NOW.plusSeconds(60)))));
        when(orders.findById(original.id())).thenReturn(Mono.just(original));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> assertThat(result.replayed()).isTrue())
                .verifyComplete();
    }

    @Test
    void retriesTransientAdmissionFailures() {
        AtomicInteger attempts = new AtomicInteger();
        // Like the real adapter (Mono.fromFuture(supplier)), every subscription performs a new call.
        when(admissions.admit(any(), any())).thenAnswer(invocation -> Mono.defer(() -> attempts.incrementAndGet() == 1
                ? Mono.<AdmissionResult>error(new TransientFailureException("conflict"))
                : Mono.<AdmissionResult>just(new AdmissionResult.Admitted(invocation.getArgument(0)))));

        StepVerifier.create(useCase.execute(command))
                .assertNext(result -> assertThat(result.dispatched()).isTrue())
                .verifyComplete();
        assertThat(attempts).hasValue(2);
    }
}
