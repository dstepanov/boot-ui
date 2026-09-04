package io.github.jdubois.bootui.micronaut.telemetry;

import io.github.jdubois.bootui.engine.telemetry.BootUiIdentitySpanProcessor;
import io.github.jdubois.bootui.engine.telemetry.BootUiSpanExporter;
import io.github.jdubois.bootui.engine.telemetry.OtelSpanEnricher;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.SpanEnricher;
import io.github.jdubois.bootui.engine.telemetry.TelemetryStore;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.inject.Singleton;

/**
 * Wires BootUI's in-process span capture into the application's OpenTelemetry SDK, which is what fills the
 * Traces and AI Framework panels.
 *
 * <p>Nothing here reaches the network: BootUI registers a {@link SpanProcessor} that copies spans into its
 * own bounded in-memory {@link TelemetryStore} as they are exported. The application's own exporters are
 * untouched, so adding BootUI never changes where its telemetry goes.
 *
 * <p>Every bean is conditioned on the OpenTelemetry SDK actually being present (it arrives with
 * {@code micronaut-tracing-opentelemetry}), so an application without tracing never links these types. The
 * same condition supplies the {@link TraceIdProvider} the capture points use to correlate a captured
 * exchange, exception or SQL statement with its owning trace.
 */
@RequiresBootUi
@Requires(classes = SpanProcessor.class)
@Factory
public class BootUiOtelFactory {

    /**
     * The processor that copies exported spans into BootUI's store. It shares the one
     * {@link SelfTelemetryClassifier} every display consumer uses, so a span the panel would hide is not
     * captured in the first place.
     */
    @Singleton
    SpanProcessor bootUiSpanProcessor(
            TelemetryStore store, MicronautTelemetrySettings settings, SelfTelemetryClassifier selfClassifier) {
        return SimpleSpanProcessor.create(new BootUiSpanExporter(store, selfClassifier, settings));
    }

    /**
     * Stamps BootUI's service/instance identity onto spans so a trace can be attributed to this application
     * in a multi-service waterfall.
     */
    @Singleton
    SpanProcessor bootUiIdentitySpanProcessor(MicronautTelemetrySettings settings, Environment environment) {
        String serviceName = environment
                .getProperty("micronaut.application.name", String.class)
                .orElse(null);
        String instanceId = System.getenv("HOSTNAME");
        return new BootUiIdentitySpanProcessor(settings, serviceName, instanceId);
    }

    @Singleton
    SpanEnricher bootUiSpanEnricher(MicronautTelemetrySettings settings) {
        return new OtelSpanEnricher(settings);
    }

    /**
     * The current trace id, read from the active OpenTelemetry span. This is what lets a captured HTTP
     * exchange, exception or SQL statement be correlated with its request even when the work happened on
     * another thread — the OpenTelemetry context propagates where a thread-local MDC would not.
     */
    @Singleton
    TraceIdProvider bootUiOtelTraceIdProvider() {
        return BootUiOtelFactory::currentSpanTraceId;
    }

    static String currentSpanTraceId() {
        try {
            SpanContext context = Span.current().getSpanContext();
            return context.isValid() ? context.getTraceId() : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
