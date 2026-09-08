package io.github.jdubois.bootui.micronaut.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.configuration.kafka.KafkaConsumerFactory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every message this application receives into the shared engine {@link KafkaActivityRecorder}, the
 * consume-side twin of {@link MicronautKafkaProducerCapture}.
 *
 * <p>Micronaut creates one {@link Consumer} bean per {@code @KafkaListener} (through
 * {@link KafkaConsumerFactory}), so the {@link BeanCreatedEventListener} seam reaches every consumer the
 * application runs — the same hook {@code micronaut-kafka} uses to install Brave tracing on consumers.
 *
 * <p><strong>Where this differs from the Spring adapter.</strong> Spring Kafka exposes a per-record
 * {@code RecordInterceptor} whose {@code success}/{@code failure} callbacks bracket the listener method, so
 * there each entry carries the listener's own outcome and duration. Micronaut's consumer runtime exposes no
 * equivalent per-record completion callback, so BootUI captures at the transport seam instead: a record is
 * recorded when {@code poll} hands it to the application, with no processing duration. A listener that then
 * <em>rejects</em> the record is recorded separately by {@link MicronautKafkaListenerFailureCapture}, which
 * observes the same delivery through {@code KafkaListenerExceptionHandler} — so a failing listener shows the
 * delivery followed by its failure rather than a single combined entry.
 *
 * <p>Capture is fail-open: a failure while reading or recording a polled record is logged at warn and the
 * records are returned to the consumer untouched.
 */
@RequiresBootUi
@Requires(classes = KafkaConsumerFactory.class)
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class MicronautKafkaConsumerCapture implements BeanCreatedEventListener<Consumer<?, ?>> {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaConsumerCapture.class);

    private final KafkaActivityRecorder recorder;

    public MicronautKafkaConsumerCapture(KafkaActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Consumer<?, ?> onCreated(BeanCreatedEvent<Consumer<?, ?>> event) {
        Consumer<?, ?> consumer = event.getBean();
        if (consumer == null || !recorder.isEnabled()) {
            return consumer;
        }
        try {
            return (Consumer<?, ?>) Proxy.newProxyInstance(
                    consumer.getClass().getClassLoader(),
                    new Class<?>[] {Consumer.class},
                    new CapturingHandler(consumer, recorder, listenerId(event)));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable Kafka consumer capture; leaving the consumer unwrapped", ex);
            return consumer;
        }
    }

    /**
     * The {@code @KafkaListener} client id Micronaut qualified this consumer bean with, which is the closest
     * thing to Spring's resolved listener id; {@code null} for an unqualified consumer.
     */
    private static String listenerId(BeanCreatedEvent<Consumer<?, ?>> event) {
        var identifier = event.getBeanIdentifier();
        return identifier == null ? null : identifier.getName();
    }

    /** Records every record {@code poll} returns, then hands the batch back untouched. */
    private record CapturingHandler(Consumer<?, ?> delegate, KafkaActivityRecorder recorder, String listenerId)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result;
            try {
                result = method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
            if ("poll".equals(method.getName()) && result instanceof ConsumerRecords<?, ?> records) {
                capture(records);
            }
            return result;
        }

        private void capture(ConsumerRecords<?, ?> records) {
            try {
                if (records.isEmpty()) {
                    return;
                }
                String groupId = groupId();
                for (ConsumerRecord<?, ?> record : records) {
                    Object key = record.key();
                    recorder.recordConsume(
                            record.topic(),
                            record.partition(),
                            record.offset(),
                            key == null ? null : String.valueOf(key),
                            null, // the transport seam knows when a record arrived, not how long it took to handle
                            true,
                            null,
                            groupId,
                            listenerId);
                }
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture an incoming Kafka message; leaving it untouched", ex);
            }
        }

        /**
         * The consumer's group id. Read on the polling thread, the only thread a {@code KafkaConsumer} may be
         * touched from, and immediately after the {@code poll} that produced these records.
         */
        private String groupId() {
            try {
                return delegate.groupMetadata().groupId();
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
