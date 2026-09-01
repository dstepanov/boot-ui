package io.github.jdubois.bootui.engine.cli;

import java.util.Objects;

/**
 * One tool invocation's answer, ready for an adapter to render.
 *
 * <p>Exactly one of {@code payload} and {@code error} is set: a successful call carries the tool's own
 * result object (which the adapter serializes with its own {@code ObjectMapper}), and a refused or failed
 * call carries a human-readable message the adapter wraps as {@code {"error": …}}.
 *
 * @param status the HTTP status to answer with
 * @param payload the tool result on success, otherwise {@code null}
 * @param error the failure message on failure, otherwise {@code null}
 */
public record CliToolResponse(CliStatus status, Object payload, String error) {

    public CliToolResponse {
        Objects.requireNonNull(status, "status");
        if (payload != null && error != null) {
            throw new IllegalArgumentException("A CLI tool response carries either a payload or an error, not both");
        }
    }

    /** A successful invocation carrying the tool's payload. */
    public static CliToolResponse success(Object payload) {
        return new CliToolResponse(CliStatus.OK, payload, null);
    }

    /** A refused or failed invocation carrying a message. */
    public static CliToolResponse failure(CliStatus status, String error) {
        return new CliToolResponse(status, null, Objects.requireNonNull(error, "error"));
    }

    /** Whether the tool ran and produced a result. */
    public boolean successful() {
        return status == CliStatus.OK;
    }
}
