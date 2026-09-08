package io.github.jdubois.bootui.micronaut.kafka;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a failed {@code @KafkaListener} delivery into the shared engine {@link KafkaActivityRecorder}, so
 * the Kafka panel shows the messages this application could not process and not only the ones it received.
 *
 * <p>{@link MicronautKafkaConsumerCapture} records a record when the transport delivers it, which is the only
 * point Micronaut's consumer runtime exposes; the listener's own outcome surfaces here instead, through the
 * {@link KafkaListenerExceptionHandler} every {@code @KafkaListener} error is routed to. A rejected record
 * therefore appears twice in the panel — once as the delivery, once as the failure — which is the honest
 * rendering of two separate observations rather than one invented combined entry.
 *
 * <p>The handler bean is wrapped rather than replaced, so the application's own error strategy (or
 * {@code micronaut-kafka}'s default) runs exactly as it would without BootUI, and a capture failure is
 * swallowed so it can never suppress the application's own error handling.
 */
@RequiresBootUi
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class MicronautKafkaListenerFailureCapture implements BeanCreatedEventListener<KafkaListenerExceptionHandler> {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautKafkaListenerFailureCapture.class);

    private final KafkaActivityRecorder recorder;

    public MicronautKafkaListenerFailureCapture(KafkaActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public KafkaListenerExceptionHandler onCreated(BeanCreatedEvent<KafkaListenerExceptionHandler> event) {
        KafkaListenerExceptionHandler handler = event.getBean();
        if (handler == null || !recorder.isEnabled()) {
            return handler;
        }
        try {
            return (KafkaListenerExceptionHandler) Proxy.newProxyInstance(
                    handler.getClass().getClassLoader(),
                    new Class<?>[] {KafkaListenerExceptionHandler.class},
                    new CapturingHandler(handler, recorder));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable Kafka listener failure capture; leaving the handler unwrapped", ex);
            return handler;
        }
    }

    /** Records the failure, then delegates to the application's own handler. */
    private record CapturingHandler(KafkaListenerExceptionHandler delegate, KafkaActivityRecorder recorder)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("handle".equals(method.getName()) && args != null && args[0] instanceof KafkaListenerException error) {
                capture(error);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
        }

        private void capture(KafkaListenerException error) {
            try {
                ConsumerRecord<?, ?> record = error.getConsumerRecord().orElse(null);
                if (record == null) {
                    // A listener that failed before (or independently of) a record — a deserialization or a
                    // subscription error — has no message to attribute the failure to.
                    return;
                }
                Object key = record.key();
                recorder.recordConsume(
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        key == null ? null : String.valueOf(key),
                        null,
                        false,
                        error.getMessage(),
                        groupId(error),
                        null);
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture a failed Kafka delivery; leaving it untouched", ex);
            }
        }

        private static String groupId(KafkaListenerException error) {
            try {
                return error.getKafkaConsumer().groupMetadata().groupId();
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }
}
