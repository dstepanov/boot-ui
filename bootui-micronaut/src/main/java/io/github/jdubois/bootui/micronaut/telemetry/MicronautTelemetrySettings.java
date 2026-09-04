package io.github.jdubois.bootui.micronaut.telemetry;

import io.github.jdubois.bootui.engine.telemetry.TelemetrySettings;
import io.micronaut.context.env.Environment;

/**
 * The bounds the in-process span capture runs under, read live from {@code bootui.telemetry.*}.
 *
 * <p>Every default matches the Spring and Quarkus adapters. The bounds are the point: BootUI keeps captured
 * traces in memory, so the number of traces, the spans per trace and the size of a single attribute value
 * are all capped — a chatty application cannot turn the Traces panel into a memory leak.
 */
public class MicronautTelemetrySettings implements TelemetrySettings {

    private final Environment environment;

    public MicronautTelemetrySettings(Environment environment) {
        this.environment = environment;
    }

    @Override
    public boolean enabled() {
        return environment
                .getProperty("bootui.telemetry.enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public boolean excludeSelfSpans() {
        return environment
                .getProperty("bootui.telemetry.exclude-self-spans", Boolean.class)
                .orElse(Boolean.TRUE);
    }

    @Override
    public int maxTraces() {
        return environment
                .getProperty("bootui.telemetry.max-traces", Integer.class)
                .orElse(500);
    }

    @Override
    public int maxSpansPerTrace() {
        return environment
                .getProperty("bootui.telemetry.max-spans-per-trace", Integer.class)
                .orElse(500);
    }

    @Override
    public int maxAttributeValueBytes() {
        return environment
                .getProperty("bootui.telemetry.max-attribute-value-bytes", Integer.class)
                .orElse(4 * 1024);
    }

    @Override
    public boolean enrichmentEnabled() {
        return environment.getProperty("bootui.telemetry.enrich", Boolean.class).orElse(Boolean.TRUE);
    }
}
