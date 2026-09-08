/**
 * In-process OpenTelemetry span capture, which fills the Traces and AI Framework panels.
 *
 * <p>The factory here binds to the OpenTelemetry SDK, a {@code provided} dependency of this adapter, so the
 * package-level {@code @Requires} below keeps it out of an application that does not add
 * {@code micronaut-tracing-opentelemetry}. See the Kafka package for the same rationale in full.
 */
@Configuration
@Requires(classes = SpanProcessor.class)
package io.github.jdubois.bootui.micronaut.telemetry;

import io.micronaut.context.annotation.Configuration;
import io.micronaut.context.annotation.Requires;
import io.opentelemetry.sdk.trace.SpanProcessor;
