package io.github.jdubois.bootui.micronaut.web;

/**
 * The property-placeholder mounts every BootUI controller is annotated with.
 *
 * <p>Micronaut resolves a {@code @Controller} path from configuration at context startup, so unlike the
 * Quarkus adapter — which pins its JAX-RS resources to a fixed {@code /bootui} mount and rewrites incoming
 * requests to it — the console's controllers bind directly to the configured mount. A custom
 * {@code bootui.path} / {@code bootui.api-path} therefore costs nothing per request and needs no rewrite
 * filter at all.
 *
 * <p>The trade-off is that a Micronaut placeholder default cannot be derived from another property: on
 * Spring and Quarkus, {@code bootui.api-path} defaults to {@code <bootui.path>/api}, but here each mount
 * carries its own literal default. {@link io.github.jdubois.bootui.micronaut.BootUiPathsValidator} closes
 * that gap by failing fast at startup when {@code bootui.path} is customized without
 * {@code bootui.api-path}, so the two can never silently disagree.
 */
public final class BootUiApiPaths {

    /** The configured UI mount, defaulting to {@code /bootui}. */
    public static final String UI = "${bootui.path:/bootui}";

    /** The configured API mount, defaulting to {@code /bootui/api}. */
    public static final String API = "${bootui.api-path:/bootui/api}";

    private BootUiApiPaths() {}
}
