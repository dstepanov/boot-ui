package io.github.jdubois.bootui.engine.mcp;

/**
 * Expected control-flow signal raised when an MCP tool refuses a request because of the request itself
 * — an unknown resource id, an unsupported argument value, a conflicting state — rather than because
 * of a server-side fault.
 *
 * <p>BootUI MCP tools delegate to the same handlers the REST API uses, and those handlers report a
 * client error by throwing their framework's own exception ({@code ResponseStatusException} on Spring,
 * {@code WebApplicationException} on Quarkus). Neither type may reach {@code bootui-engine}, so each
 * adapter translates a 4xx failure into this canonical exception at its tool-registration boundary and
 * {@link McpDispatcher} turns it into an in-band {@link McpDispatchOutcome.ToolCallError} the agent can
 * read, instead of the detail-free JSON-RPC internal error reserved for genuine server faults.
 *
 * <p>Only client (4xx) statuses are representable: a server-side failure must keep its original
 * throwable so it is logged and reported as an internal error.
 */
public final class McpToolClientException extends RuntimeException {

    private final int status;

    /**
     * @param status the canonical client-error status, in {@code [400, 500)}
     * @param message the safe, human-readable reason already exposed over REST; a {@code null} or blank
     *     message falls back to {@link McpProtocol#TOOL_CALL_FAILED_MESSAGE}
     * @throws IllegalArgumentException if {@code status} is not a 4xx status
     */
    public McpToolClientException(int status, String message) {
        super(
                message == null || message.isBlank() ? McpProtocol.TOOL_CALL_FAILED_MESSAGE : message,
                null,
                false,
                false);
        if (!McpToolClientExceptions.isClientError(status)) {
            throw new IllegalArgumentException("MCP tool client error status must be 4xx, but was: " + status);
        }
        this.status = status;
    }

    /** The canonical client-error status this tool asked for (e.g. {@code 404}). */
    public int status() {
        return status;
    }
}
