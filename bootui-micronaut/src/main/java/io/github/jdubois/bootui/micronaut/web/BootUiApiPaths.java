package io.github.jdubois.bootui.micronaut.web;

/**
 * The property-placeholder mounts every BootUI controller is annotated with.
 *
 * <p>Micronaut resolves a {@code @Controller} path from configuration at context startup, so unlike the
 * Quarkus adapter — which pins its JAX-RS resources to a fixed {@code /bootui} mount and rewrites incoming
 * requests to it — the console's controllers bind directly to the configured mount. A custom
 * {@code bootui.path} / {@code bootui.api-path} therefore costs nothing per request, needs no rewrite
 * filter at all, and leaves BootUI occupying exactly the mounts it is configured with: with
 * {@code bootui.path=/console}, nothing of the console is served under {@code /bootui}.
 *
 * <p>A Micronaut placeholder default cannot be derived from another property — the resolver does not
 * evaluate a nested {@code ${...}} inside a default — so the literal default below is only ever the
 * fallback. {@link io.github.jdubois.bootui.micronaut.BootUiApiPathConfigurer} contributes
 * {@code bootui.api-path} = {@code <bootui.path>/api} to the environment before any controller's metadata
 * is resolved, so the API mount follows a moved UI mount exactly as it does on Spring and Quarkus, and an
 * operator only ever has to set {@code bootui.path}.
 */
public final class BootUiApiPaths {

    /** The configured UI mount, defaulting to {@code /bootui}. */
    public static final String UI = "${bootui.path:/bootui}";

    /**
     * The configured API mount. The literal default applies only when {@code bootui.path} is also at its
     * default; otherwise the derived {@code <bootui.path>/api} contributed at startup resolves here.
     */
    public static final String API = "${bootui.api-path:/bootui/api}";

    private BootUiApiPaths() {}
}
