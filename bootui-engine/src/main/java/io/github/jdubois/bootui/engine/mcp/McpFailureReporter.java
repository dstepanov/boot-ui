package io.github.jdubois.bootui.engine.mcp;

/**
 * Receives unexpected MCP failures for server-side diagnostics.
 *
 * <p>Implementations must retain the original throwable and stack trace. The operation is fixed
 * server-generated context and never contains client input.
 */
@FunctionalInterface
public interface McpFailureReporter {

    /** Reports one unexpected failure at the boundary that converts it to a safe JSON-RPC error. */
    void report(String operation, Throwable failure);
}
