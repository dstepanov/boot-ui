package io.github.jdubois.bootui.engine.mcp;

import java.util.List;

/**
 * The typed outcome of an {@link McpDispatcher} evaluation. The adapter renders each variant to JSON,
 * echoing the original request id and serializing any tool payload with its own {@code ObjectMapper}.
 *
 * <p>Variants map directly to the JSON-RPC wire shape:
 *
 * <ul>
 *   <li>{@link NoResponse} — a notification; the transport emits no body (HTTP 202).
 *   <li>{@link InitializeResult}/{@link PingResult}/{@link ToolsListResult}/{@link PromptsListResult}/
 *       {@link PromptGetResult}/{@link ToolCallResult} — a JSON-RPC {@code result} envelope.
 *   <li>{@link ToolCallError} — a {@code result} carrying {@code isError:true} (an in-band tool
 *       failure the agent can read).
 *   <li>{@link ProtocolError} — a JSON-RPC {@code error} envelope ({@code code}/{@code message}).
 * </ul>
 */
public sealed interface McpDispatchOutcome
        permits McpDispatchOutcome.NoResponse,
                McpDispatchOutcome.InitializeResult,
                McpDispatchOutcome.PingResult,
                McpDispatchOutcome.ToolsListResult,
                McpDispatchOutcome.PromptsListResult,
                McpDispatchOutcome.PromptGetResult,
                McpDispatchOutcome.ToolCallResult,
                McpDispatchOutcome.ToolCallError,
                McpDispatchOutcome.ProtocolError {

    /** A notification: no response is emitted. */
    record NoResponse() implements McpDispatchOutcome {}

    /**
     * The {@code initialize} result.
     *
     * @param protocolVersion the negotiated protocol revision (requested, or the default)
     * @param serverName the advertised server name
     * @param serverVersion the advertised server version
     * @param instructions the advertised usage instructions (framework-specific copy)
     */
    record InitializeResult(String protocolVersion, String serverName, String serverVersion, String instructions)
            implements McpDispatchOutcome {}

    /** The {@code ping} result (an empty object). */
    record PingResult() implements McpDispatchOutcome {}

    /**
     * The {@code tools/list} result.
     *
     * @param tools the advertised tool descriptors, in catalog order
     */
    record ToolsListResult(List<McpToolDescriptor> tools) implements McpDispatchOutcome {}

    /** The {@code prompts/list} result. */
    record PromptsListResult(List<McpPrompt> prompts) implements McpDispatchOutcome {}

    /** The {@code prompts/get} result. */
    record PromptGetResult(McpPrompt prompt) implements McpDispatchOutcome {}

    /**
     * A successful {@code tools/call}: the adapter serializes {@code payload} to a single text content
     * block with {@code isError:false}.
     *
     * @param payload the serializable tool result (typically a BootUI core DTO)
     */
    record ToolCallResult(Object payload) implements McpDispatchOutcome {}

    /**
     * A failed {@code tools/call} reported in-band ({@code isError:true}): a refused gate, a busy
     * single-flight action, or a client error the tool itself raised. The agent reads {@code message} as
     * text content.
     *
     * <p>{@code status} is metadata for non-MCP consumers of the same dispatch outcome (the BootUI CLI
     * maps it to its own exit status). Both MCP codecs ignore it, so the JSON-RPC wire shape is
     * identical whether or not it is present.
     *
     * @param message the human-readable failure reason
     * @param status the canonical client-error status the tool asked for (e.g. {@code 404}), or {@code
     *     null} when the failure has no such status
     */
    record ToolCallError(String message, Integer status) implements McpDispatchOutcome {

        /** A failure with no canonical client-error status. */
        public ToolCallError(String message) {
            this(message, null);
        }
    }

    /**
     * A JSON-RPC protocol error (an {@code error} envelope).
     *
     * @param code the JSON-RPC error code (see {@link McpProtocol})
     * @param message the human-readable error message; unexpected failures use the detail-free {@link
     *     McpProtocol#INTERNAL_ERROR_MESSAGE}
     */
    record ProtocolError(int code, String message) implements McpDispatchOutcome {}
}
