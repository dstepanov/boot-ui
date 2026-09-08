package io.github.jdubois.bootui.micronaut.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.configuration.kafka.KafkaProducerFactory;
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
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every message this application publishes into the shared engine {@link KafkaActivityRecorder},
 * which backs the dedicated Kafka panel and Live Activity's {@code MESSAGING} entries.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code KafkaProducerCaptureBeanPostProcessor} and the
 * Quarkus adapter's {@code QuarkusKafkaProducerCapture}. Micronaut creates every {@link Producer} — the one
 * behind a {@code @KafkaClient} interface as well as a directly injected one — as a bean, so the
 * {@link BeanCreatedEventListener} seam captures the whole publish path from one place; it is the same hook
 * {@code micronaut-kafka} itself uses to install Brave tracing on producers, and the same one BootUI's
 * SQL-trace binding uses on datasources.
 *
 * <p>The producer is wrapped in a JDK proxy rather than a hand-written delegate because {@link Producer} is
 * a wide interface of which only {@code send} is of interest; everything else — {@code beginTransaction},
 * {@code flush}, {@code partitionsFor}, {@code close} — passes straight through, unobserved and untimed.
 *
 * <p>Both {@code send} overloads are routed through {@link Producer#send(ProducerRecord, Callback)} with a
 * capturing callback composed in front of the application's own (or none), which is what gives producer
 * sends a real duration here: unlike Spring's {@code ProducerListener}, the callback is paired with a
 * send-start timestamp taken on the calling thread. Capture is fail-open — a failure while reading or
 * recording the record is logged at warn and the application's callback still runs — so diagnostics can
 * never cost the application a message.
 */
@RequiresBootUi
@Requires(classes = KafkaProducerFactory.class)
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class MicronautKafkaProducerCapture implements BeanCreatedEventListener<Producer<?, ?>> {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaProducerCapture.class);

    private final KafkaActivityRecorder recorder;

    public MicronautKafkaProducerCapture(KafkaActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public Producer<?, ?> onCreated(BeanCreatedEvent<Producer<?, ?>> event) {
        Producer<?, ?> producer = event.getBean();
        if (producer == null || !recorder.isEnabled()) {
            return producer;
        }
        try {
            return (Producer<?, ?>) Proxy.newProxyInstance(
                    producer.getClass().getClassLoader(),
                    new Class<?>[] {Producer.class},
                    new CapturingHandler(producer, recorder));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable Kafka producer capture; leaving the producer unwrapped", ex);
            return producer;
        }
    }

    /** Times each send from the call to its broker acknowledgement, then delegates to the app's callback. */
    private record CapturingHandler(Producer<?, ?> delegate, KafkaActivityRecorder recorder)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("send".equals(method.getName()) && args != null && args[0] instanceof ProducerRecord<?, ?> record) {
                Callback applicationCallback = args.length > 1 ? (Callback) args[1] : null;
                return send(record, applicationCallback);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
        }

        @SuppressWarnings("unchecked")
        private Object send(ProducerRecord<?, ?> record, Callback applicationCallback) {
            long startNanos = System.nanoTime();
            Callback capturing = (metadata, exception) -> {
                capture(record, metadata, exception, (System.nanoTime() - startNanos) / 1_000_000L);
                if (applicationCallback != null) {
                    applicationCallback.onCompletion(metadata, exception);
                }
            };
            // The one-argument send() is specified as send(record, null), so routing both overloads through
            // the two-argument form changes nothing the caller can observe: the returned Future is the same.
            return ((Producer<Object, Object>) delegate).send((ProducerRecord<Object, Object>) record, capturing);
        }

        private void capture(ProducerRecord<?, ?> record, RecordMetadata metadata, Exception exception, long millis) {
            try {
                // Boxing metadata.partition() explicitly keeps both ternary branches Integer; mixing an
                // Integer branch with a raw int one would make the conditional expression's own type int
                // (JLS 15.25 binary numeric promotion) and unbox a null record.partition().
                Integer partition = metadata == null ? record.partition() : Integer.valueOf(metadata.partition());
                Object key = record.key();
                recorder.recordProduce(
                        record.topic(),
                        partition,
                        key == null ? null : String.valueOf(key),
                        millis,
                        exception == null,
                        exception == null ? null : exception.getMessage());
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture an outgoing Kafka message; leaving it untouched", ex);
            }
        }
    }
}
