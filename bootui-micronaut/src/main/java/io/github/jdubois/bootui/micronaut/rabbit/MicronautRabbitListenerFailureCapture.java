package io.github.jdubois.bootui.micronaut.rabbit;

import com.rabbitmq.client.Envelope;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.rabbitmq.bind.RabbitConsumerState;
import io.micronaut.rabbitmq.exception.RabbitListenerException;
import io.micronaut.rabbitmq.exception.RabbitListenerExceptionHandler;
import jakarta.inject.Singleton;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a failed {@code @RabbitListener} delivery into the shared engine {@link RabbitActivityRecorder}, so
 * the RabbitMQ panel shows the messages this application could not process and not only the ones it received.
 *
 * <p>{@code micronaut-rabbitmq} catches a listener's exception inside its own consumer and routes it to the
 * {@link RabbitListenerExceptionHandler}, so it never reaches the channel seam
 * {@link MicronautRabbitChannelPoolCapture} observes: without this the panel would render every delivery as
 * successful. A rejected message therefore appears twice — once as the delivery, once as the failure — which
 * is the honest rendering of two separate observations rather than one invented combined entry.
 *
 * <p>The handler bean is wrapped rather than replaced, so the application's own error handling (or
 * {@code micronaut-rabbitmq}'s default) runs exactly as it would without BootUI, and a capture failure is
 * swallowed so it can never suppress it.
 */
@RequiresBootUi
@Requires(classes = RabbitListenerExceptionHandler.class)
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class MicronautRabbitListenerFailureCapture implements BeanCreatedEventListener<RabbitListenerExceptionHandler> {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautRabbitListenerFailureCapture.class);

    private final RabbitActivityRecorder recorder;

    public MicronautRabbitListenerFailureCapture(RabbitActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public RabbitListenerExceptionHandler onCreated(BeanCreatedEvent<RabbitListenerExceptionHandler> event) {
        RabbitListenerExceptionHandler handler = event.getBean();
        if (handler == null || !recorder.isEnabled()) {
            return handler;
        }
        try {
            return (RabbitListenerExceptionHandler) Proxy.newProxyInstance(
                    handler.getClass().getClassLoader(),
                    new Class<?>[] {RabbitListenerExceptionHandler.class},
                    new CapturingHandler(handler, recorder));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable RabbitMQ listener failure capture; leaving the handler unwrapped", ex);
            return handler;
        }
    }

    /** Records the failure, then delegates to the application's own handler. */
    private record CapturingHandler(RabbitListenerExceptionHandler delegate, RabbitActivityRecorder recorder)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("handle".equals(method.getName()) && args != null && args[0] instanceof RabbitListenerException error) {
                capture(error);
            }
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
        }

        private void capture(RabbitListenerException error) {
            try {
                RabbitConsumerState state = error.getMessageState().orElse(null);
                if (state == null) {
                    // A listener that failed independently of a message — a binding or a subscription error —
                    // has nothing to attribute the failure to.
                    return;
                }
                Envelope envelope = state.getEnvelope();
                recorder.recordConsume(
                        envelope == null ? null : envelope.getExchange(),
                        envelope == null ? null : envelope.getRoutingKey(),
                        null,
                        null,
                        false,
                        error.getMessage(),
                        state.getProperties() == null
                                ? null
                                : state.getProperties().getCorrelationId());
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture a failed RabbitMQ delivery; leaving it untouched", ex);
            }
        }
    }
}
