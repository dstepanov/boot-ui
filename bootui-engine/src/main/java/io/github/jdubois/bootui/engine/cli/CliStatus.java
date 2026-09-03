package io.github.jdubois.bootui.engine.cli;

/**
 * The HTTP statuses the {@code /bootui/api/cli} facade answers with.
 *
 * <p>Modelled as an enum rather than raw integers so the mapping is exhaustive and testable in the engine,
 * where no framework {@code HttpStatus} type is available.
 */
public enum CliStatus {
    /** The tool ran; the body is its payload. */
    OK(200),
    /** The arguments were rejected before the tool ran. */
    BAD_REQUEST(400),
    /** The tool's panel is disabled, or it is an action on a read-only panel. */
    FORBIDDEN(403),
    /** This instance does not advertise a tool under that name. */
    NOT_FOUND(404),
    /** The same action is already running. */
    CONFLICT(409),
    /** The concurrent tool-call budget is exhausted. */
    TOO_MANY_REQUESTS(429),
    /** The tool ran and failed, or the dispatcher answered something a tool call cannot produce. */
    INTERNAL_SERVER_ERROR(500),
    /** The CLI facade is disabled. */
    SERVICE_UNAVAILABLE(503),
    /** The tool exceeded its execution-time budget. */
    GATEWAY_TIMEOUT(504);

    private final int code;

    CliStatus(int code) {
        this.code = code;
    }

    /** The numeric HTTP status code. */
    public int code() {
        return code;
    }

    /**
     * The constant for a client-error status an MCP tool raised while it was running.
     *
     * <p>{@code 404} is deliberately <em>not</em> passed through. On this facade a {@code 404} means "this
     * instance does not advertise a tool under that name", and the CLI renders it exactly that way — it
     * would tell the reader the command does not exist, and drop the tool's message, when in truth only an
     * argument was wrong. A tool that ran and could not find what the caller named is reporting a bad
     * argument, so it is answered as {@link #BAD_REQUEST} with its own message intact.
     *
     * <p>Any other 4xx this facade does not model falls back to {@link #BAD_REQUEST} too. {@code
     * McpToolClientException} already guarantees the status is in {@code [400, 500)}, so the fallback stays
     * inside the client-error class: a tool answering, say, {@code 422} is reported as the caller's fault,
     * never as a server fault.
     *
     * @throws IllegalArgumentException if {@code code} is not a 4xx status
     */
    public static CliStatus forClientError(int code) {
        if (code < 400 || code >= 500) {
            throw new IllegalArgumentException("CLI client-error status must be 4xx, but was: " + code);
        }
        if (code == NOT_FOUND.code) {
            return BAD_REQUEST;
        }
        for (CliStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return BAD_REQUEST;
    }
}
