package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.micronaut.core.value.PropertyResolver;

/**
 * Single source of truth for normalized Micronaut UI, API, and application-root path composition.
 *
 * <p>Unlike the Quarkus adapter — whose JAX-RS resources are pinned to a fixed internal {@code /bootui}
 * mount, with requests to a custom {@code bootui.path} rewritten onto it — every Micronaut controller,
 * including the one serving the packaged SPA bundle, binds directly to the {@code bootui.path} /
 * {@code bootui.api-path} placeholders (see {@link io.github.jdubois.bootui.micronaut.web.BootUiApiPaths}).
 * There is consequently no second, reserved mount on Micronaut: with {@code bootui.path=/console} nothing
 * of BootUI is served under {@code /bootui} at all.
 *
 * <p>That is why the matcher below claims exactly the configured mounts and nothing else. Claiming a
 * fixed {@code /bootui} on top of them would hijack an application's own routes at that path — the guards
 * would reject them as non-loopback and the security rule would open them to anonymous callers — for a
 * surface BootUI does not even occupy.
 */
public final class MicronautBootUiPaths {

    public static final String PATH_KEY = "bootui.path";
    public static final String API_PATH_KEY = "bootui.api-path";

    /** The API mount BootUI falls back to: the default UI mount plus {@code /api}. */
    public static final String DEFAULT_API_PATH = BootUiPathNormalizer.DEFAULT_PATH + "/api";

    private MicronautBootUiPaths() {}

    public static String uiPath(PropertyResolver config) {
        String configured = string(config, PATH_KEY);
        return BootUiPathNormalizer.normalize(configured == null ? BootUiPathNormalizer.DEFAULT_PATH : configured);
    }

    /**
     * The configured API mount, defaulting to {@code <bootui.path>/api}.
     *
     * <p>The same derivation {@link BootUiApiPathConfigurer} contributes to the environment as an actual
     * {@code bootui.api-path} property, so this method and the mount the controllers bind to always agree,
     * whether or not the operator set the key.
     */
    public static String apiPath(PropertyResolver config) {
        String configured = string(config, API_PATH_KEY);
        return BootUiPathNormalizer.normalizeApiPath(configured == null ? uiPath(config) + "/api" : configured);
    }

    public static String applicationPath(PropertyResolver config, String path) {
        return contextPrefix(config) + path;
    }

    public static String applicationUiPath(PropertyResolver config) {
        return applicationPath(config, uiPath(config));
    }

    public static String applicationApiPath(PropertyResolver config) {
        return applicationPath(config, apiPath(config));
    }

    /** Validates the configured mounts, throwing {@link IllegalArgumentException} on an invalid value. */
    public static void validate(PropertyResolver config) {
        uiPath(config);
        apiPath(config);
    }

    /**
     * Whether {@code path} — a full request path, which still carries the
     * {@code micronaut.server.context-path} prefix — targets BootUI's own console surface.
     *
     * <p>This is the single matcher every self-traffic exclusion in the Micronaut adapter uses — HTTP
     * exchange capture, exception capture, log-based exception capture, and outbound REST-client capture —
     * so those capture points can never drift from each other, nor from the access filters and the
     * security rule that guard the very same surface.
     *
     * <p>The surface is exactly the configured UI mount and the configured API mount, under the configured
     * context path, whose prefix is stripped first exactly as the access filters strip it. Nothing else is
     * claimed: a console moved to {@code /console} leaves an application's own {@code /bootui} routes
     * entirely alone.
     *
     * <p>Matching is a strict {@code /}-delimited prefix match on the request path only, so an unrelated
     * application path such as {@code /bootui-other} is never mistaken for BootUI traffic and stays
     * captured. Invalid configuration degrades to the default mounts rather than throwing: a capture filter
     * must never disrupt a request, and the default is also what the controllers fall back to.
     */
    public static boolean isBootUiRequest(PropertyResolver config, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        if (config == null) {
            return isSameOrChild(path, BootUiPathNormalizer.DEFAULT_PATH) || isSameOrChild(path, DEFAULT_API_PATH);
        }
        String relativePath;
        try {
            relativePath = MicronautContextPath.stripPrefix(path, contextPrefix(config));
        } catch (RuntimeException exception) {
            relativePath = path;
        }
        String uiPath;
        String apiPath;
        try {
            uiPath = safeUiPath(config);
            apiPath = safeApiPath(config);
        } catch (RuntimeException exception) {
            uiPath = BootUiPathNormalizer.DEFAULT_PATH;
            apiPath = DEFAULT_API_PATH;
        }
        return isSameOrChild(relativePath, uiPath) || isSameOrChild(relativePath, apiPath);
    }

    public static boolean isSameOrChild(String path, String basePath) {
        return path != null && basePath != null && (path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    public static String contextPrefix(PropertyResolver config) {
        return MicronautContextPath.normalize(string(config, MicronautContextPath.CONTEXT_PATH_KEY));
    }

    public static String safeUiPath(PropertyResolver config) {
        try {
            return uiPath(config);
        } catch (IllegalArgumentException exception) {
            return BootUiPathNormalizer.DEFAULT_PATH;
        }
    }

    public static String safeApiPath(PropertyResolver config) {
        try {
            return apiPath(config);
        } catch (IllegalArgumentException exception) {
            return DEFAULT_API_PATH;
        }
    }

    private static String string(PropertyResolver config, String key) {
        return config.getProperty(key, String.class).orElse(null);
    }
}
