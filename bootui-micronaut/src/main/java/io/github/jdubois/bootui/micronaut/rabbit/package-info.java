/**
 * RabbitMQ message capture, which fills the RabbitMQ panel and Live Activity's {@code MESSAGING} entries.
 *
 * <p>Every bean here binds to {@code micronaut-rabbitmq}, a {@code provided} dependency of this adapter, so
 * the package-level {@code @Requires} below is what keeps the slice out of an application that does not use
 * RabbitMQ — declared once here rather than repeated on each class. See the Kafka package for the same
 * rationale in full.
 *
 * <p>{@code rabbitmq.enabled=false} switches {@code micronaut-rabbitmq} itself off, and the capture beans
 * follow it: with no channel pool being created there is nothing for them to wrap.
 */
@Configuration
@Requires(classes = ChannelPool.class)
@Requires(property = "rabbitmq.enabled", notEquals = StringUtils.FALSE)
package io.github.jdubois.bootui.micronaut.rabbit;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.util.StringUtils;
import io.micronaut.rabbitmq.connect.ChannelPool;
