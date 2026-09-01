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
}
