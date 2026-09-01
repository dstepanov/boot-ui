package io.github.jdubois.bootui.client;

/**
 * What happened to a tool call, as the command-line endpoint reports it in the HTTP status.
 *
 * <p>The distinction that matters to a caller is between "the tool ran and answered" and "BootUI declined to
 * run it", because the second is a configuration statement about the target application rather than a
 * failure of the request. A CI job that asks a read-only panel to run a scan wants to know that, not to see
 * a generic error.
 */
public enum ToolOutcome {

    /** The tool ran and its payload is available. */
    SUCCESS,

    /** The request was malformed: an unknown argument, a wrong type, or a missing required id. */
    INVALID_REQUEST,

    /** No such tool on this instance. */
    UNKNOWN_TOOL,

    /** The backing panel is disabled, or the panel is read-only and the tool is an action. */
    REFUSED_BY_POLICY,

    /** The action is already running, or the endpoint is at its concurrency limit. */
    BUSY,

    /** The tool exceeded the execution budget. */
    TIMED_OUT,

    /** The command-line endpoint is disabled on this instance. */
    ENDPOINT_DISABLED,

    /** The tool failed inside the application, or the server answered something unexpected. */
    SERVER_ERROR;

    /** The outcome an HTTP status maps to. */
    public static ToolOutcome fromStatus(int status) {
        if (status >= 200 && status < 300) {
            return SUCCESS;
        }
        switch (status) {
            case 400:
                return INVALID_REQUEST;
            case 401:
            case 403:
                // 401 arrives when bootui.authentication.token is required and absent or wrong; 403 is
                // either that same rejection at the filter, or the per-panel enable/read-only refusal.
                return REFUSED_BY_POLICY;
            case 404:
                return UNKNOWN_TOOL;
            case 409:
            case 429:
                return BUSY;
            case 503:
                return ENDPOINT_DISABLED;
            case 504:
                return TIMED_OUT;
            default:
                return SERVER_ERROR;
        }
    }

    /** Whether the tool answered. */
    public boolean successful() {
        return this == SUCCESS;
    }
}
