package io.github.jdubois.bootui.micronaut.cli;

import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.MicronautPanelAccessConfig;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.mcp.MicronautMcpFailureReporter;
import io.github.jdubois.bootui.micronaut.mcp.MicronautMcpPanelPolicy;
import io.github.jdubois.bootui.micronaut.mcp.MicronautMcpTools;
import io.github.jdubois.bootui.spi.McpPanelPolicy;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.time.Duration;

/**
 * Wires the endpoint the {@code bootui} command-line client calls.
 *
 * <p>The CLI answers the same questions as the MCP server and is projected mechanically from the same tool
 * registry, so the two can never drift: a diagnostic reachable from an agent is reachable from a terminal,
 * under the same per-panel enable/read-only policy and the same call, concurrency and timeout bounds.
 *
 * <p>Unlike MCP it is on by default, because it needs no client to be installed in the application and
 * exposes nothing MCP would not — it is still behind BootUI's localhost guard and panel policy.
 */
@RequiresBootUi
@Factory
public class BootUiCliFactory {

    @Singleton
    CliService cliService(
            MicronautMcpTools tools, Environment environment, MicronautMcpFailureReporter failureReporter) {
        McpPanelPolicy policy = new MicronautMcpPanelPolicy(new MicronautPanelAccessConfig(environment));
        return new CliService(
                enabled(environment),
                tools::tools,
                policy,
                serverVersion(),
                MicronautBootUiPaths.applicationApiPath(environment) + "/cli",
                maxResults(environment),
                maxConcurrentCalls(environment),
                executionTimeoutMillis(environment),
                failureReporter);
    }

    private static boolean enabled(Environment environment) {
        return environment.getProperty("bootui.cli.enabled", Boolean.class).orElse(true);
    }

    private static int maxResults(Environment environment) {
        return Math.max(
                1,
                environment.getProperty("bootui.cli.max-results", Integer.class).orElse(200));
    }

    private static int maxConcurrentCalls(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.cli.max-concurrent-calls", Integer.class)
                        .orElse(McpProtocol.DEFAULT_MAX_CONCURRENT_CALLS));
    }

    private static long executionTimeoutMillis(Environment environment) {
        return Math.max(
                1,
                environment
                        .getProperty("bootui.cli.execution-timeout", Duration.class)
                        .orElse(Duration.ofMillis(McpProtocol.DEFAULT_EXECUTION_TIMEOUT_MILLIS))
                        .toMillis());
    }

    private static String serverVersion() {
        return BootUiCliFactory.class.getPackage().getImplementationVersion();
    }
}
