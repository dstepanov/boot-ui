package io.github.jdubois.bootui.webfluxsample.errors;

/**
 * Sample error payload returned by a reactive handler that builds its own response body.
 *
 * @param code a stable machine-readable error code
 * @param message a human-readable description
 */
public record SampleErrorBody(String code, String message) {}
