package io.github.jdubois.bootui.micronautsample;

import io.github.jdubois.bootui.conformance.AbstractMcpConformanceTest;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;

/**
 * Runs the shared MCP contract against the Micronaut adapter: the JSON-RPC envelope, the payload budget,
 * the disabled-server short circuit and the panel policy an agent sees must be the same on every stack.
 *
 * <p>{@code bootui.mcp.max-payload-bytes=256} shrinks the budget so the oversized-request case is
 * exercised without sending a large body, exactly as in the Spring and Quarkus runners.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "bootui.panels.copilot.enabled", value = "false")
@Property(name = "bootui.panels.heap-dump.read-only", value = "true")
@Property(name = "bootui.heap-dump.capture-enabled", value = "false")
@Property(name = "bootui.claude-code.enabled", value = "OFF")
@Property(name = "bootui.mcp.max-payload-bytes", value = "256")
class MicronautMcpConformanceTest extends AbstractMcpConformanceTest {

    @Inject
    EmbeddedServer server;

    @Override
    protected String baseUrl() {
        return server.getURL().toExternalForm();
    }
}
