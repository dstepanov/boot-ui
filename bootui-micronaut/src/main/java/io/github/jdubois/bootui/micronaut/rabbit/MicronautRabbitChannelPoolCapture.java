package io.github.jdubois.bootui.micronaut.rabbit;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.order.Ordered;
import io.micronaut.rabbitmq.connect.ChannelPool;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records every AMQP message this application publishes and receives into the shared engine
 * {@link RabbitActivityRecorder}, which backs the dedicated RabbitMQ panel and Live Activity's
 * {@code MESSAGING} entries.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code RabbitProducerCaptureBeanPostProcessor} /
 * {@code RabbitConsumerCaptureBeanPostProcessor} pair. Micronaut has no publish or delivery callback to hook,
 * but everything {@code micronaut-rabbitmq} does — a {@code @RabbitClient} publish (which goes through
 * {@code ReactorReactivePublisher}), a {@code @RabbitListener} subscription, and any hand-written channel
 * work — draws its {@link Channel} from the {@link ChannelPool}. Wrapping that one bean therefore captures
 * both directions from a single seam, using the same {@link BeanCreatedEventListener} hook
 * {@code micronaut-rabbitmq} itself uses to install Micrometer metrics on the connection factory.
 *
 * <p>Channels are wrapped in a JDK proxy because {@link Channel} is a very wide interface of which only
 * {@code basicPublish} and {@code basicConsume} are of interest; queue/exchange declaration, acknowledgement,
 * transactions and everything else pass straight through. A returned channel is recognised and never wrapped
 * twice, so a channel that cycles through the pool keeps exactly one layer of capture.
 *
 * <p>A publish is timed around the {@code basicPublish} call and recorded as failed if it throws. A delivery
 * is timed around the consumer callback, so its duration covers the listener dispatch — for a reactive
 * listener that is the dispatch rather than the full processing. Listener failures are recorded by
 * {@link MicronautRabbitListenerFailureCapture}, because {@code micronaut-rabbitmq} handles them inside the
 * callback rather than letting them propagate here.
 *
 * <p>Capture is fail-open throughout: a capture-side failure is logged at warn and the application's own
 * publish or delivery proceeds untouched.
 */
@RequiresBootUi
@Requires(classes = ChannelPool.class)
@Singleton
@Order(Ordered.LOWEST_PRECEDENCE)
public class MicronautRabbitChannelPoolCapture implements BeanCreatedEventListener<ChannelPool> {

    private static final Logger LOG = LoggerFactory.getLogger(MicronautRabbitChannelPoolCapture.class);

    private final RabbitActivityRecorder recorder;

    public MicronautRabbitChannelPoolCapture(RabbitActivityRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public ChannelPool onCreated(BeanCreatedEvent<ChannelPool> event) {
        ChannelPool pool = event.getBean();
        if (pool == null || !recorder.isEnabled()) {
            return pool;
        }
        return new CapturingChannelPool(pool, recorder);
    }

    /** Hands out capturing channels; everything else is the pool's own behaviour. */
    private record CapturingChannelPool(ChannelPool delegate, RabbitActivityRecorder recorder) implements ChannelPool {

        @Override
        public Channel getChannel() throws IOException {
            return wrap(delegate.getChannel(), recorder);
        }

        @Override
        public void returnChannel(Channel channel) {
            delegate.returnChannel(channel);
        }

        @Override
        public boolean isTopologyRecoveryEnabled() {
            return delegate.isTopologyRecoveryEnabled();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }
    }

    /** Marker for a channel BootUI already wrapped, so a pooled channel is never wrapped twice. */
    private interface CapturingChannel {}

    private static Channel wrap(Channel channel, RabbitActivityRecorder recorder) {
        if (channel == null || channel instanceof CapturingChannel) {
            return channel;
        }
        try {
            return (Channel) Proxy.newProxyInstance(
                    channel.getClass().getClassLoader(),
                    new Class<?>[] {Channel.class, CapturingChannel.class},
                    new ChannelHandler(channel, recorder));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable RabbitMQ capture for a channel; leaving it unwrapped", ex);
            return channel;
        }
    }

    /** Observes {@code basicPublish} and wraps the consumer handed to {@code basicConsume}. */
    private record ChannelHandler(Channel delegate, RabbitActivityRecorder recorder) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && "basicPublish".equals(method.getName())) {
                return publish(method, args);
            }
            if (args != null && "basicConsume".equals(method.getName())) {
                args = withCapturingConsumer(args);
            }
            return call(method, args);
        }

        private Object publish(Method method, Object[] args) throws Throwable {
            // Every basicPublish overload starts (exchange, routingKey, ...) and carries the properties
            // immediately before the body, which is always last.
            String exchange = (String) args[0];
            String routingKey = (String) args[1];
            String correlationId = correlationId(args.length < 2 ? null : args[args.length - 2]);
            long startNanos = System.nanoTime();
            try {
                Object result = call(method, args);
                capturePublish(exchange, routingKey, correlationId, startNanos, null);
                return result;
            } catch (Throwable failure) {
                capturePublish(exchange, routingKey, correlationId, startNanos, failure);
                throw failure;
            }
        }

        /**
         * Replaces the {@link Consumer} argument, when the chosen {@code basicConsume} overload takes one,
         * with a capturing wrapper. The callback-based overloads carry no {@code Consumer} and are left
         * alone; {@code micronaut-rabbitmq} always uses the {@code Consumer} form.
         */
        private Object[] withCapturingConsumer(Object[] args) {
            int last = args.length - 1;
            if (last < 1 || !(args[last] instanceof Consumer consumer) || consumer instanceof CapturingConsumer) {
                return args;
            }
            Object[] replaced = args.clone();
            replaced[last] = wrapConsumer(consumer, String.valueOf(args[0]), recorder);
            return replaced;
        }

        private Object call(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() == null ? ex : ex.getCause();
            }
        }

        private void capturePublish(
                String exchange, String routingKey, String correlationId, long startNanos, Throwable failure) {
            try {
                recorder.recordPublish(
                        exchange,
                        routingKey,
                        (System.nanoTime() - startNanos) / 1_000_000L,
                        failure == null,
                        failure == null ? null : failure.getMessage(),
                        correlationId);
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture an outgoing RabbitMQ message; leaving it untouched", ex);
            }
        }
    }

    /** Marker for a consumer BootUI already wrapped. */
    private interface CapturingConsumer {}

    private static Consumer wrapConsumer(Consumer consumer, String queue, RabbitActivityRecorder recorder) {
        try {
            return (Consumer) Proxy.newProxyInstance(
                    consumer.getClass().getClassLoader(),
                    new Class<?>[] {Consumer.class, CapturingConsumer.class},
                    new ConsumerHandler(consumer, queue, recorder));
        } catch (RuntimeException ex) {
            LOG.warn("BootUI could not enable RabbitMQ capture for a consumer; leaving it unwrapped", ex);
            return consumer;
        }
    }

    /** Times and records each delivery, then hands it to the application's consumer. */
    private record ConsumerHandler(Consumer delegate, String queue, RabbitActivityRecorder recorder)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            boolean delivery = "handleDelivery".equals(method.getName()) && args != null && args.length > 2;
            long startNanos = System.nanoTime();
            try {
                Object result;
                try {
                    result = method.invoke(delegate, args);
                } catch (InvocationTargetException ex) {
                    throw ex.getCause() == null ? ex : ex.getCause();
                }
                if (delivery) {
                    captureDelivery(args, startNanos, null);
                }
                return result;
            } catch (Throwable failure) {
                if (delivery) {
                    captureDelivery(args, startNanos, failure);
                }
                throw failure;
            }
        }

        private void captureDelivery(Object[] args, long startNanos, Throwable failure) {
            try {
                // handleDelivery(consumerTag, envelope, properties, body)
                Envelope envelope = args[1] instanceof Envelope value ? value : null;
                recorder.recordConsume(
                        envelope == null ? null : envelope.getExchange(),
                        envelope == null ? null : envelope.getRoutingKey(),
                        queue,
                        (System.nanoTime() - startNanos) / 1_000_000L,
                        failure == null,
                        failure == null ? null : failure.getMessage(),
                        correlationId(args[2]));
            } catch (RuntimeException ex) {
                LOG.warn("BootUI could not capture an incoming RabbitMQ message; leaving it untouched", ex);
            }
        }
    }

    /** The AMQP correlation id carried by a message's properties, or {@code null} when there is none. */
    private static String correlationId(Object properties) {
        return properties instanceof AMQP.BasicProperties basicProperties ? basicProperties.getCorrelationId() : null;
    }
}
