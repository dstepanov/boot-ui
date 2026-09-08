/**
 * Kafka message capture, which fills the Kafka panel and Live Activity's {@code MESSAGING} entries.
 *
 * <p>Every bean here binds to {@code micronaut-kafka}, which this adapter declares as a {@code provided}
 * dependency: it is on the compiler's classpath so the bindings can be written against the real API, and
 * absent from an application that does not use Kafka. The package-level {@code @Requires} below is what
 * makes that safe — without it these beans would be created in an application with no Kafka on its
 * classpath and fail with {@code NoClassDefFoundError} at startup. It is declared once, here, rather than
 * repeated on each class, which is also what keeps the whole slice switching on and off together.
 *
 * <p>{@code kafka.enabled=false} switches {@code micronaut-kafka} itself off, and the capture beans follow
 * it: with no producers or consumers being created there is nothing for them to wrap.
 */
@Configuration
@Requires(classes = KafkaProducerFactory.class)
@Requires(property = "kafka.enabled", notEquals = StringUtils.FALSE)
package io.github.jdubois.bootui.micronaut.kafka;

import io.micronaut.configuration.kafka.KafkaProducerFactory;
import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
