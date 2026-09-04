package io.github.jdubois.bootui.micronaut.mcp;

import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpGuidance;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.micronaut.MicronautPanelAccessConfig;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Wires the BootUI MCP server for Micronaut.
 *
 * <p>The server is <strong>off by default</strong> ({@code bootui.mcp.enabled} defaults to {@code OFF}) and
 * must be turned on deliberately, because it lets a local AI agent run BootUI's diagnostics and read runtime
 * state. Every bound the other adapters apply applies here too: a cap on results per call, on concurrent
 * calls, on payload and response size, and an execution timeout — so one agent cannot saturate the
 * application it is inspecting. Per-panel enable/read-only policy is enforced through the same
 * {@link MicronautPanelAccessConfig} the HTTP surface uses, so a panel an operator disabled is not reachable
 * through MCP either.
 */
@RequiresBootUi
@Factory
public class BootUiMcpFactory {

    private static final String FRAMEWORK = "Micronaut";

    private static final String INSTRUCTIONS = McpGuidance.instructions(FRAMEWORK);

    /** The live on/off state, initialized from configuration and toggleable from the MCP Server panel. */
    @Singleton
    McpServerState mcpServerState(Environment environment) {
        String mode =
                environment.getProperty("bootui.mcp.enabled", String.class).orElse("OFF");
        return new McpServerState(mode);
    }

    @Singleton
    McpDispatcher mcpDispatcher(
            MicronautMcpTools tools, Environment environment, MicronautMcpFailureReporter failureReporter) {
        int maxResults =
                environment.getProperty("bootui.mcp.max-results", Integer.class).orElse(200);
        McpPanelPolicy policy = new MicronautMcpPanelPolicy(new MicronautPanelAccessConfig(environment));
        return new McpDispatcher(
                tools::tools,
                McpGuidance.prompts(FRAMEWORK),
                policy,
                serverVersion(),
                INSTRUCTIONS,
                maxResults,
                maxConcurrentCalls(environment),
                executionTimeoutMillis(environment),
                failureReporter);
    }

    public static int maxConcurrentCalls(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.mcp.max-concurrent-calls", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS));
    }

    public static int maxPayloadBytes(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.mcp.max-payload-bytes", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_PAYLOAD_BYTES));
    }

    public static int maxResponseBytes(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.mcp.max-response-bytes", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_RESPONSE_BYTES));
    }

    public static long executionTimeoutMillis(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.mcp.execution-timeout", Duration.class)
                        .orElse(Duration.ofMillis(McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS))
                        .toMillis());
    }

    private static String serverVersion() {
        return BootUiMcpFactory.class.getPackage().getImplementationVersion();
    }
}
