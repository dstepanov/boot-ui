package io.github.jdubois.bootui.micronautsample;

import io.github.jdubois.bootui.conformance.AbstractCliConformanceTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

/**
 * Runs the shared CLI contract against the Micronaut adapter, so a {@code bootui} CLI built once behaves
 * identically here and on Spring and Quarkus — including the HTTP status each refusal produces, which is
 * what a shell actually branches on.
 *
 * <p>{@code bootui.panels.memory.enabled=false} and {@code bootui.panels.heap-dump.read-only=true} arm the
 * suite's panel-policy refusal cases, exactly as they do in the Spring and Quarkus runners.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "bootui.panels.memory.enabled", value = "false")
@Property(name = "bootui.panels.heap-dump.read-only", value = "true")
@Property(name = "bootui.heap-dump.capture-enabled", value = "false")
@Property(name = "bootui.claude-code.enabled", value = "OFF")
class MicronautCliConformanceTest extends AbstractCliConformanceTest {

    @Inject
    EmbeddedServer server;

    @Override
    protected String baseUrl() {
        return server.getURL().toExternalForm();
    }
}
