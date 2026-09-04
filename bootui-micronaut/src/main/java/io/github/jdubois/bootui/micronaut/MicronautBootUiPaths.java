package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.micronaut.core.value.PropertyResolver;

/**
 * Single source of truth for normalized Micronaut UI, API, and application-root path composition — the
 * analogue of the Quarkus adapter's {@code QuarkusBootUiPaths}.
 */
public final class MicronautBootUiPaths {

    public static final String PATH_KEY = "bootui.path";
    public static final String API_PATH_KEY = "bootui.api-path";

    /** Internal, fixed mount serving BootUI's controllers and static assets. */
    static final String INTERNAL_UI_PATH = BootUiPathNormalizer.DEFAULT_PATH;

    static final String INTERNAL_API_PATH = INTERNAL_UI_PATH + "/api";

    private MicronautBootUiPaths() {}

    public static String uiPath(PropertyResolver config) {
        String configured = string(config, PATH_KEY);
        return BootUiPathNormalizer.normalize(configured == null ? BootUiPathNormalizer.DEFAULT_PATH : configured);
    }

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
     * exchange capture, exception capture, and log-based exception capture — so those capture points can
     * never drift from each other, nor from the access filters that guard the very same surface. It
     * recognizes a BootUI request in all three mount shapes: the internal {@code /bootui} and
     * {@code /bootui/api} mounts (always registered), the operator-configured {@code bootui.path} /
     * {@code bootui.api-path} mounts, and any of the above under a non-default context path, whose prefix
     * is stripped first exactly as the access filters strip it.
     *
     * <p>Matching is a strict {@code /}-delimited prefix match on the request path only, so an unrelated
     * application path such as {@code /bootui-other} is never mistaken for BootUI traffic and stays
     * captured. Invalid configuration degrades to the internal mounts rather than throwing: a capture
     * filter must never disrupt a request, and defaulting narrowly keeps application traffic captured.
     */
    public static boolean isBootUiRequest(PropertyResolver config, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String relativePath = path;
        String uiPath = INTERNAL_UI_PATH;
        String apiPath = INTERNAL_API_PATH;
        if (config != null) {
            try {
                relativePath = MicronautContextPath.stripPrefix(path, contextPrefix(config));
            } catch (RuntimeException exception) {
                relativePath = path;
            }
            try {
                uiPath = safeUiPath(config);
                apiPath = safeApiPath(config);
            } catch (RuntimeException exception) {
                uiPath = INTERNAL_UI_PATH;
                apiPath = INTERNAL_API_PATH;
            }
        }
        return isSameOrChild(relativePath, INTERNAL_UI_PATH)
                || isSameOrChild(relativePath, INTERNAL_API_PATH)
                || isSameOrChild(relativePath, uiPath)
                || isSameOrChild(relativePath, apiPath);
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
            return BootUiPathNormalizer.DEFAULT_PATH + "/api";
        }
    }

    private static String string(PropertyResolver config, String key) {
        return config.getProperty(key, String.class).orElse(null);
    }
}
