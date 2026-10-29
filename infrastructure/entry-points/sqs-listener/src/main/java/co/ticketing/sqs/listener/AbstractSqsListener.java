package co.ticketing.sqs.listener;

import co.ticketing.messaging.CommandMessageCodec;
import co.ticketing.model.order.OrderCommand;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Template Method for SQS consumers: receive (long polling) -> decode -> {@link #handle} -> acknowledge.
 *
 * <ul>
 *   <li>The message is deleted ONLY after the handler succeeded (at-least-once). Business rejections
 *       are successful processing; only technical failures leave the message for redelivery.</li>
 *   <li>Undecodable messages are never acknowledged, so SQS moves them to the DLQ after maxReceiveCount.</li>
 *   <li>{@code flatMap(..., concurrency)} bounds the work in flight per instance (bulkhead).</li>
 *   <li>Graceful shutdown: stop receiving, finish the messages in flight, then report stopped.</li>
 * </ul>
 */
public abstract class AbstractSqsListener implements SmartLifecycle {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final SqsAsyncClient sqs;
    private final String queueName;
    private final String queueUrl;
    private final ListenerSettings settings;
    private final CommandMessageCodec codec;
    private final MeterRegistry meters;
    private final AtomicReference<Runnable> onStopped = new AtomicReference<>();
    private volatile Sinks.One<Boolean> stopSignal = Sinks.one();
    private volatile boolean running;

    protected AbstractSqsListener(SqsAsyncClient sqs, String queueName, String queueUrl, ListenerSettings settings,
                                  CommandMessageCodec codec, MeterRegistry meters) {
        this.sqs = sqs;
        this.queueName = queueName;
        this.queueUrl = queueUrl;
        this.settings = settings;
        this.codec = codec;
        this.meters = meters;
    }

    /** Variable step of the template: process one command and return an outcome label for metrics. */
    protected abstract Mono<String> handle(OrderCommand command);

    @Override
    public void start() {
        stopSignal = Sinks.one();
        running = true;
        consume(stopSignal.asMono())
                // Never run the receive loop on the caller thread: completed futures would make it synchronous.
                .subscribeOn(Schedulers.parallel())
                .doFinally(signal -> {
                    running = false;
                    Runnable callback = onStopped.getAndSet(null);
                    if (callback != null) {
                        callback.run();
                    }
                })
                .subscribe();
        log.info("Listening to queue {}", queueName);
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    @Override
    public void stop(Runnable callback) {
        if (!running) {
            callback.run();
            return;
        }
        onStopped.set(callback);
        stopSignal.tryEmitValue(Boolean.TRUE);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    Flux<String> consume(Mono<Boolean> stop) {
        return Flux.defer(this::receiveBatch)
                .repeat()
                .takeUntilOther(stop)
                .flatMap(this::process, settings.concurrency());
    }

    Flux<Message> receiveBatch() {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl)
                .maxNumberOfMessages(settings.maxMessages())
                .waitTimeSeconds(settings.waitTimeSeconds())
                .messageAttributeNames("All")
                .build();
        return Mono.fromFuture(() -> sqs.receiveMessage(request))
                .flatMapIterable(ReceiveMessageResponse::messages)
                .onErrorResume(error -> {
                    log.warn("Receive from {} failed; retrying after {}", queueName, settings.errorBackoff(), error);
                    return Mono.delay(settings.errorBackoff()).thenMany(Flux.<Message>empty());
                });
    }

    Mono<String> process(Message message) {
        return Mono.defer(() -> handle(codec.decode(message.body())))
                .timeout(settings.processingTimeout())
                .flatMap(outcome -> acknowledge(message).thenReturn(outcome))
                .doOnNext(this::count)
                .onErrorResume(error -> {
                    count("failed");
                    log.error("Message {} from {} not acknowledged; it will be redelivered", message.messageId(), queueName, error);
                    return Mono.empty();
                });
    }

    private Mono<Void> acknowledge(Message message) {
        DeleteMessageRequest request = DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build();
        return Mono.fromFuture(() -> sqs.deleteMessage(request)).then();
    }

    private void count(String outcome) {
        meters.counter("ticketing.messages", "queue", queueName, "outcome", outcome).increment();
    }
}
