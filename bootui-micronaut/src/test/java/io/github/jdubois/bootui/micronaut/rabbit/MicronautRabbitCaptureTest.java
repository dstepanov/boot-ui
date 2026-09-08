package io.github.jdubois.bootui.micronaut.rabbit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.Envelope;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder.CapturedMessage;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.rabbitmq.bind.RabbitConsumerState;
import io.micronaut.rabbitmq.connect.ChannelPool;
import io.micronaut.rabbitmq.exception.RabbitListenerException;
import io.micronaut.rabbitmq.exception.RabbitListenerExceptionHandler;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pins what the Micronaut RabbitMQ bindings record: a publish through a pooled channel, a delivery through
 * the consumer that channel subscribed, and a rejected message as a separate failure entry. Each listener is
 * driven directly over a stub bean, which is exactly what Micronaut hands it at bean-creation time.
 */
@Tag("rabbitmq")
class MicronautRabbitCaptureTest {

    private static final AMQP.BasicProperties PROPERTIES =
            new AMQP.BasicProperties.Builder().correlationId("corr-1").build();

    private final RabbitActivityRecorder recorder = new RabbitActivityRecorder(true, true, 100, 64);

    @Test
    void recordsAPublishThroughAPooledChannel() throws Exception {
        Channel channel = mock(Channel.class);

        Channel wrapped = pool(channel).getChannel();
        wrapped.basicPublish("orders-exchange", "orders.created", PROPERTIES, new byte[] {1});

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(RabbitActivityRecorder.Direction.PUBLISH);
        assertThat(message.exchange()).isEqualTo("orders-exchange");
        assertThat(message.routingKey()).isEqualTo("orders.created");
        assertThat(message.success()).isTrue();
        assertThat(message.durationMillis()).isNotNull();
    }

    @Test
    void recordsAPublishThatFailedAndStillPropagatesTheFailure() throws Exception {
        Channel channel = mock(Channel.class);
        doThrow(new IOException("broker gone")).when(channel).basicPublish(anyString(), anyString(), any(), any());

        Channel wrapped = pool(channel).getChannel();
        assertThatThrownBy(() -> wrapped.basicPublish("orders-exchange", "orders.created", PROPERTIES, new byte[] {1}))
                .isInstanceOf(IOException.class);

        CapturedMessage message = single();
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("Message processing failed");
    }

    @Test
    void recordsADeliveryThroughTheConsumerTheChannelSubscribed() throws Exception {
        AtomicReference<Consumer> subscribed = new AtomicReference<>();
        Channel channel = mock(Channel.class);
        when(channel.basicConsume(anyString(), anyBoolean(), any(Consumer.class)))
                .thenAnswer(invocation -> {
                    subscribed.set(invocation.getArgument(2));
                    return "tag";
                });
        Consumer application = mock(Consumer.class);

        Channel wrapped = pool(channel).getChannel();
        wrapped.basicConsume("orders-queue", true, application);
        subscribed
                .get()
                .handleDelivery(
                        "tag", new Envelope(1L, false, "orders-exchange", "orders.created"), PROPERTIES, new byte[] {1
                        });

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(RabbitActivityRecorder.Direction.CONSUME);
        assertThat(message.exchange()).isEqualTo("orders-exchange");
        assertThat(message.routingKey()).isEqualTo("orders.created");
        assertThat(message.queue()).isEqualTo("orders-queue");
        assertThat(message.durationMillis()).isNotNull();
        assertThat(message.success()).isTrue();
    }

    @Test
    void neverWrapsAPooledChannelTwice() throws Exception {
        Channel channel = mock(Channel.class);
        ChannelPool delegate = mock(ChannelPool.class);
        when(delegate.getChannel()).thenReturn(channel);
        ChannelPool pool = new MicronautRabbitChannelPoolCapture(recorder).onCreated(event(delegate));

        Channel first = pool.getChannel();
        // returnChannel hands BootUI's own wrapper back to the pool, which then offers it out again.
        pool.returnChannel(first);
        when(delegate.getChannel()).thenReturn(first);

        assertThat(pool.getChannel()).isSameAs(first);
    }

    @Test
    void recordsARejectedMessageAsASeparateFailureAndStillCallsTheApplicationsHandler() {
        AtomicReference<RabbitListenerException> delegated = new AtomicReference<>();
        RabbitListenerExceptionHandler handler = delegated::set;
        RabbitListenerException error = new RabbitListenerException(
                "listener blew up",
                new IllegalStateException(),
                new Object(),
                new RabbitConsumerState(
                        new Envelope(1L, false, "orders-exchange", "orders.created"),
                        PROPERTIES,
                        new byte[] {1},
                        mock(Channel.class)));

        new MicronautRabbitListenerFailureCapture(recorder)
                .onCreated(event(handler))
                .handle(error);

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(RabbitActivityRecorder.Direction.CONSUME);
        assertThat(message.exchange()).isEqualTo("orders-exchange");
        assertThat(message.success()).isFalse();
        assertThat(delegated.get()).isSameAs(error);
    }

    @Test
    void leavesTheChannelPoolAloneWhenCaptureIsDisabled() {
        RabbitActivityRecorder disabled = new RabbitActivityRecorder(false, true, 100, 64);
        ChannelPool pool = mock(ChannelPool.class);

        assertThat(new MicronautRabbitChannelPoolCapture(disabled).onCreated(event(pool)))
                .isSameAs(pool);
    }

    private ChannelPool pool(Channel channel) throws IOException {
        ChannelPool delegate = mock(ChannelPool.class);
        when(delegate.getChannel()).thenReturn(channel);
        return new MicronautRabbitChannelPoolCapture(recorder).onCreated(event(delegate));
    }

    private CapturedMessage single() {
        assertThat(recorder.recent()).hasSize(1);
        return recorder.recent().get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> BeanCreatedEvent<T> event(T bean) {
        BeanCreatedEvent<T> event = mock(BeanCreatedEvent.class);
        when(event.getBean()).thenReturn(bean);
        return event;
    }
}
