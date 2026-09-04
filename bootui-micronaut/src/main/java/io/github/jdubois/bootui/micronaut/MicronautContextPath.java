package io.github.jdubois.bootui.micronaut;

/**
 * Shared helper for stripping the configured {@code micronaut.server.context-path} prefix from a request
 * path.
 *
 * <p>Micronaut mounts the whole application — including BootUI's controllers and its static UI — under
 * {@code micronaut.server.context-path}, so under a non-default context path (e.g. {@code /app}) the
 * console is served at {@code /app/bootui/**} while the server filters that guard it still see the full
 * path. {@link BootUiMicronautSafetyFilter}, {@link MicronautPanelAccessFilter} and
 * {@link BootUiProdShellGuardFilter} all need to strip this prefix before applying their own path-based
 * checks; this class is the single implementation so the filters can never silently drift in how they
 * interpret a non-default context path.
 *
 * <p>This is the Micronaut analogue of the Quarkus adapter's {@code QuarkusRootPath}, which does the same
 * job for {@code quarkus.http.root-path}, and of the Spring adapter stripping the servlet context path.
 */
final class MicronautContextPath {

    static final String CONTEXT_PATH_KEY = "micronaut.server.context-path";

    private MicronautContextPath() {}

    /**
     * Normalizes a {@code micronaut.server.context-path} value to a strip-prefix ({@code ""} for the
     * default). A missing/blank value normalizes to {@code ""} (no prefix), which still guards the default
     * {@code /bootui} surface — the context path is read live and <em>fails closed</em>.
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.equals("/") ? "" : trimmed;
    }

    /**
     * Removes {@code contextPathPrefix} (already {@link #normalize(String) normalized}) from
     * {@code path} so path-based checks are context-path-relative. Without stripping, a filter matching on
     * a literal {@code /bootui} prefix would not recognize the prefixed path and would be skipped entirely
     * (fail-open).
     */
    static String stripPrefix(String path, String contextPathPrefix) {
        if (path == null) {
            return null;
        }
        if (contextPathPrefix.isEmpty()) {
            return path;
        }
        if (path.equals(contextPathPrefix)) {
            return "/";
        }
        if (path.startsWith(contextPathPrefix + "/")) {
            return path.substring(contextPathPrefix.length());
        }
        return path;
    }
}
