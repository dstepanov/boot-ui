package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.panel.BootUiGlobalWritePolicy;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.panel.BootUiPanels.Panel;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Applies per-panel enabled and read-only settings to BootUI API routes — the Micronaut analogue of the
 * Spring adapter's {@code PanelAccessFilter} and the Quarkus adapter's {@code QuarkusPanelAccessFilter},
 * at behavioral parity: same config keys ({@code bootui.panels.<id>.enabled} / {@code .read-only}, plus
 * the global {@code bootui.read-only}), same panel resolution via the shared
 * {@link BootUiPanels#byApiPath(String)} registry, and the same canonical JSON 403 body shape
 * ({@code {"error":"BootUI panel access denied","panel":"<id>","reason":"<reason>"}}).
 *
 * <p>It runs at a <strong>later</strong> order than {@link BootUiMicronautSafetyFilter} so the
 * localhost/Host/CSRF checks always evaluate first: a request that fails both the safety guard and panel
 * gating is rejected by the safety filter, which short-circuits before this filter runs.
 *
 * <p>Only requests under the configured API mount are considered (mirroring the Spring filter's
 * {@code shouldNotFilter}); the SPA shell and its bundle, served from the UI mount, are never gated by a
 * panel toggle. A request path that does not resolve to a registered {@link Panel} — notably {@code GET
 * /api/overview} (the Overview panel registers no API prefix on purpose), the {@code /api/panels} manifest
 * endpoint itself, and any future non-panel endpoint — passes through untouched, exactly like the Spring
 * and Quarkus filters.
 */
@RequiresBootUi
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(MicronautPanelAccessFilter.ORDER)
public class MicronautPanelAccessFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Runs after the safety filter ({@value BootUiMicronautSafetyFilter#ORDER}). */
    static final int ORDER = -950;

    private final Environment environment;
    private final MicronautPanelAccessConfig accessConfig;

    public MicronautPanelAccessFilter(Environment environment) {
        this.environment = environment;
        this.accessConfig = new MicronautPanelAccessConfig(environment);
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filterRequest(HttpRequest<?> request) {
        String apiRelativePath = apiRelativePath(request);
        if (apiRelativePath == null) {
            return null;
        }

        String method = request.getMethodName();
        if (!SAFE_METHODS.contains(method) && accessConfig.isGlobalReadOnly()) {
            var globalSubject = BootUiGlobalWritePolicy.subjectFor(apiRelativePath);
            if (globalSubject.isPresent()) {
                return blockedResponse(globalSubject.get(), accessConfig.panelReadOnlyReason(globalSubject.get()));
            }
        }

        Panel panel = BootUiPanels.byApiPath(apiRelativePath).orElse(null);
        if (panel == null) {
            return null;
        }

        if (!accessConfig.isPanelEnabled(panel.id())) {
            return blockedResponse(panel.id(), accessConfig.panelDisabledReason(panel.id()));
        }

        if (panel.actionCapable() && !SAFE_METHODS.contains(method) && accessConfig.isPanelReadOnly(panel.id())) {
            return blockedResponse(panel.id(), accessConfig.panelReadOnlyReason(panel.id()));
        }

        return null;
    }

    /**
     * Resolves the request path relative to the configured API mount, after stripping the configured
     * {@code micronaut.server.context-path} prefix (shared with {@link BootUiMicronautSafetyFilter} via
     * {@link MicronautContextPath}). Returns {@code null} when the request is not under the API mount at
     * all, including requests to the static UI shell — which is never gated by a panel toggle. The mount is
     * read live and fails closed to the default {@code /bootui/api}.
     */
    @Nullable
    private String apiRelativePath(HttpRequest<?> request) {
        String path =
                MicronautContextPath.stripPrefix(request.getPath(), MicronautContextPath.normalize(contextPath()));
        if (path == null) {
            return null;
        }
        String apiPath = MicronautBootUiPaths.safeApiPath(environment);
        if (path.equals(apiPath)) {
            return "/";
        }
        if (!path.startsWith(apiPath + "/")) {
            return null;
        }
        return path.substring(apiPath.length());
    }

    private String contextPath() {
        return environment
                .getProperty(MicronautContextPath.CONTEXT_PATH_KEY, String.class)
                .orElse("/");
    }

    private HttpResponse<String> blockedResponse(String panelId, String reason) {
        return HttpResponse.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body("{\"error\":\"" + escape("BootUI panel access denied") + "\",\"panel\":\"" + escape(panelId)
                        + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
