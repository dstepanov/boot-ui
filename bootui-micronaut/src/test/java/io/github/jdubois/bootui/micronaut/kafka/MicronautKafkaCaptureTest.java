package io.github.jdubois.bootui.micronaut.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder.CapturedMessage;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerException;
import io.micronaut.configuration.kafka.exceptions.KafkaListenerExceptionHandler;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.inject.BeanIdentifier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

/**
 * Pins what the Micronaut Kafka bindings record: a producer send with its broker outcome and a real
 * duration, a polled record as a delivery, and a rejected record as a separate failure. Each listener is
 * driven directly over a stub bean, which is exactly what Micronaut hands it at bean-creation time.
 */
class MicronautKafkaCaptureTest {

    private final KafkaActivityRecorder recorder = new KafkaActivityRecorder(true, true, 100, 16);

    @Test
    void recordsAProducerSendWhenTheBrokerAcknowledgesIt() throws Exception {
        AtomicReference<Callback> installed = new AtomicReference<>();
        Producer<Object, Object> producer = mock(Producer.class);
        when(producer.send(any(), any())).thenAnswer(invocation -> {
            installed.set(invocation.getArgument(1));
            return null;
        });

        Producer<Object, Object> wrapped =
                untyped(new MicronautKafkaProducerCapture(recorder).onCreated(event(producer, "orders")));
        wrapped.send(new ProducerRecord<>("orders", 0, "k1", "payload"));
        installed.get().onCompletion(new RecordMetadata(new TopicPartition("orders", 3), 12L, 0, 0L, 0, 0), null);

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(KafkaActivityRecorder.Direction.PRODUCE);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.partition()).isEqualTo(3);
        assertThat(message.success()).isTrue();
        assertThat(message.durationMillis()).isNotNull();
        // Only a bounded hash of the key is ever retained, never the key itself.
        assertThat(message.key()).isNotNull().isNotEqualTo("k1");
    }

    @Test
    void recordsAFailedProducerSendWithoutLeakingTheBrokersMessage() throws Exception {
        AtomicReference<Callback> installed = new AtomicReference<>();
        Producer<Object, Object> producer = mock(Producer.class);
        when(producer.send(any(), any())).thenAnswer(invocation -> {
            installed.set(invocation.getArgument(1));
            return null;
        });

        Producer<Object, Object> wrapped =
                untyped(new MicronautKafkaProducerCapture(recorder).onCreated(event(producer, "orders")));
        wrapped.send(new ProducerRecord<>("orders", "k1", "payload"));
        installed.get().onCompletion(null, new IllegalStateException("broker said no"));

        CapturedMessage message = single();
        assertThat(message.success()).isFalse();
        assertThat(message.errorMessage()).isEqualTo("Message processing failed");
    }

    @Test
    void recordsEveryPolledRecordAsADelivery() {
        Consumer<Object, Object> consumer = mock(Consumer.class);
        TopicPartition partition = new TopicPartition("orders", 1);
        when(consumer.poll(any(Duration.class)))
                .thenReturn(new ConsumerRecords<>(
                        Map.of(partition, List.of(new ConsumerRecord<>("orders", 1, 7L, "k1", "payload")))));
        when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("orders-group"));

        Consumer<?, ?> wrapped =
                new MicronautKafkaConsumerCapture(recorder).onCreated(event(consumer, "orders-client"));
        wrapped.poll(Duration.ofMillis(1));

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(KafkaActivityRecorder.Direction.CONSUME);
        assertThat(message.topic()).isEqualTo("orders");
        assertThat(message.partition()).isEqualTo(1);
        assertThat(message.offset()).isEqualTo(7L);
        assertThat(message.groupId()).isEqualTo("orders-group");
        assertThat(message.listenerId()).isEqualTo("orders-client");
        assertThat(message.success()).isTrue();
        // The transport seam sees the delivery, not how long the listener took.
        assertThat(message.durationMillis()).isNull();
    }

    @Test
    void recordsARejectedRecordAsASeparateFailureAndStillCallsTheApplicationsHandler() {
        AtomicReference<KafkaListenerException> delegated = new AtomicReference<>();
        KafkaListenerExceptionHandler handler = delegated::set;
        Consumer<Object, Object> consumer = mock(Consumer.class);
        when(consumer.groupMetadata()).thenReturn(new ConsumerGroupMetadata("orders-group"));
        KafkaListenerException error = new KafkaListenerException(
                "listener blew up",
                new IllegalStateException(),
                new Object(),
                consumer,
                new ConsumerRecord<>("orders", 1, 7L, "k1", "payload"));

        new MicronautKafkaListenerFailureCapture(recorder)
                .onCreated(event(handler, "handler"))
                .handle(error);

        CapturedMessage message = single();
        assertThat(message.direction()).isEqualTo(KafkaActivityRecorder.Direction.CONSUME);
        assertThat(message.offset()).isEqualTo(7L);
        assertThat(message.success()).isFalse();
        assertThat(delegated.get()).isSameAs(error);
    }

    @Test
    void leavesEverythingAloneWhenCaptureIsDisabled() {
        KafkaActivityRecorder disabled = new KafkaActivityRecorder(false, true, 100, 16);
        Producer<Object, Object> producer = mock(Producer.class);

        assertThat(new MicronautKafkaProducerCapture(disabled).onCreated(event(producer, "orders")))
                .isSameAs(producer);
    }

    @SuppressWarnings("unchecked")
    private static Producer<Object, Object> untyped(Producer<?, ?> producer) {
        return (Producer<Object, Object>) producer;
    }

    private CapturedMessage single() {
        assertThat(recorder.recent()).hasSize(1);
        return recorder.recent().get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> BeanCreatedEvent<T> event(T bean, String name) {
        BeanCreatedEvent<T> event = mock(BeanCreatedEvent.class);
        BeanIdentifier identifier = mock(BeanIdentifier.class);
        when(identifier.getName()).thenReturn(name);
        when(event.getBean()).thenReturn(bean);
        when(event.getBeanIdentifier()).thenReturn(identifier);
        return event;
    }
}
