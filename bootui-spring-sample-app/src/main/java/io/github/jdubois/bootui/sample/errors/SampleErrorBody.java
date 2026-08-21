package io.github.jdubois.bootui.sample.errors;

/**
 * Sample error payload returned by a handler that builds its own response body, so the BootUI
 * error-contract catalogue can show a {@code CUSTOM_OBJECT} body category next to {@code PROBLEM_DETAIL}
 * ones.
 *
 * @param code a stable machine-readable error code
 * @param message a human-readable description
 */
public record SampleErrorBody(String code, String message) {}
