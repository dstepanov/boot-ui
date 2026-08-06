package io.smallrye.reactive.messaging.rabbitmq;

import com.rabbitmq.client.BasicProperties;
import com.rabbitmq.client.Envelope;

public final class IncomingRabbitMQMetadataTestFactory {

    private IncomingRabbitMQMetadataTestFactory() {}

    public static IncomingRabbitMQMetadata create(BasicProperties properties, Envelope envelope) {
        return new IncomingRabbitMQMetadata(properties, envelope);
    }
}
