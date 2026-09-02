package io.github.jdubois.bootui.engine.mcp;

/**
 * Shared helper for the adapter-side translation of a framework client error into {@link
 * McpToolClientException}.
 *
 * <p>Each adapter catches its own framework exception ({@code ResponseStatusException} on Spring,
 * {@code WebApplicationException} on Quarkus) and must apply the same rule about which statuses are the
 * request's fault, so the three stacks answer identically. The rule itself carries no framework type and
 * lives here rather than being restated per adapter.
 */
public final class McpToolClientExceptions {

    private McpToolClientExceptions() {}

    /**
     * Whether {@code status} is a client (4xx) error, and therefore a statement about the request that
     * should reach the agent in-band rather than an unexpected server fault.
     */
    public static boolean isClientError(int status) {
        return status >= 400 && status <= 499;
    }
}
