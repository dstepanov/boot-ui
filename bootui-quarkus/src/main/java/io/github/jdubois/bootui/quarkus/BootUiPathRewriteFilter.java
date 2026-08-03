package io.github.jdubois.bootui.quarkus;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;

/**
 * Rewrites incoming requests at a non-default {@code bootui.path} to the internal {@code /bootui}
 * mount before route dispatch, so all JAX-RS resources (hardcoded at {@code /bootui/api/**}) and the
 * built-in static-resource handler (serving {@code META-INF/resources/bootui/} at {@code /bootui/**})
 * continue to work without annotation changes.
 *
 * <p>This filter is a <strong>no-op</strong> when {@code bootui.path} is the default ({@code /bootui})
 * or unset, so it has zero runtime cost in the common case. When a custom path is configured, it:</p>
 * <ol>
 *   <li>Blocks direct requests to the internal {@code /bootui/**} path with {@code 404}, preventing
 *       unintended access to the classpath static assets at a path the operator did not configure.</li>
 *   <li>Rewrites requests whose path starts with the configured base path to the internal path via
 *       {@link RoutingContext#reroute(String)}, triggering a new routing cycle. The new cycle's
 *       {@link BootUiQuarkusSafetyFilter} and {@link QuarkusPanelAccessFilter} see the rewritten
 *       {@code /bootui/**} path, which their hardcoded path constants already match.</li>
 * </ol>
 *
 * <p>Infinite-loop safety: {@link RoutingContext#reroute} creates a new {@link RoutingContext} but
 * reuses the same underlying data map, so the {@link #REROUTE_MARKER} key set before calling reroute
 * is visible to this filter in the new routing cycle, which then passes through immediately.</p>
 *
 * <p>This filter is registered <strong>only in dev/test</strong> launch modes (it is one of the beans
 * wired by the same {@code registerConsole} build step that gates the rest of the console), so the prod
 * guard ({@link BootUiProdShellGuardFilter}) remains the sole authority in production. The two filters
 * have complementary roles: this one enables custom-path access in dev, the prod guard suppresses the
 * whole surface in production regardless of path.</p>
 *
 * <p>Priority {@value #PRIORITY} is intentionally higher than the safety filter ({@code 1000}) and all
 * other BootUI filters, so the rewrite happens before any access-control decision.</p>
 */
@ApplicationScoped
public class BootUiPathRewriteFilter {

    static final String PATH_KEY = "bootui.path";

    /** Internal classpath path — the fixed mount for all JAX-RS resources and static assets. */
    static final String INTERNAL_PATH = "/bootui";

    /**
     * Data-map key written to the {@link RoutingContext} before calling {@code reroute()}.
     * The value persists into the new routing cycle (the data map is shared), so this filter
     * recognises and passes through its own rerouted requests without entering an infinite loop.
     */
    static final String REROUTE_MARKER = "bootui-rerouted";

    /** Runs before all other BootUI filters so the rewrite precedes every access-control decision. */
    private static final int PRIORITY = 2000;

    private final Config config;
    private final LaunchMode launchMode;

    @Inject
    public BootUiPathRewriteFilter(Config config, LaunchMode launchMode) {
        this.config = config;
        this.launchMode = launchMode;
    }

    public void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext rc) {
        // Production: this filter is never wired (prod-dark via build step), but defensive check.
        if (launchMode == LaunchMode.NORMAL) {
            rc.next();
            return;
        }

        String configuredPath = bootUiPath();

        // Fast path: default path — no rewrite needed.
        if (INTERNAL_PATH.equals(configuredPath)) {
            rc.next();
            return;
        }

        // This routing cycle was started by our own reroute() call — pass through immediately to
        // avoid an infinite loop. The data map persists across reroute() calls.
        if (Boolean.TRUE.equals(rc.get(REROUTE_MARKER))) {
            rc.next();
            return;
        }

        String fullPath = rc.normalizedPath();
        if (fullPath == null) {
            rc.next();
            return;
        }

        // Strip the root-path prefix (quarkus.http.root-path) so comparisons are against the
        // application-relative path, exactly as every other BootUI Vert.x filter does.
        String rootPath = QuarkusRootPath.normalize(
                config.getOptionalValue(QuarkusRootPath.ROOT_PATH_KEY, String.class).orElse("/"));
        String relativePath = QuarkusRootPath.stripPrefix(fullPath, rootPath);

        // Block direct access to the internal /bootui path when a custom path is configured, to
        // avoid exposing the classpath static assets at a path the operator did not intend.
        if (relativePath.equals(INTERNAL_PATH) || relativePath.startsWith(INTERNAL_PATH + "/")) {
            rc.response().setStatusCode(404).end();
            return;
        }

        // Rewrite the configured path to the internal path.
        if (relativePath.equals(configuredPath) || relativePath.startsWith(configuredPath + "/")) {
            String suffix = relativePath.substring(configuredPath.length()); // "" or "/.."
            // Build the reroute target including the root-path prefix (reroute() takes a full path).
            String reroutePath = rootPath + INTERNAL_PATH + suffix;
            rc.put(REROUTE_MARKER, Boolean.TRUE);
            rc.reroute(reroutePath);
            return;
        }

        rc.next();
    }

    private String bootUiPath() {
        return config.getOptionalValue(PATH_KEY, String.class).orElse(INTERNAL_PATH);
    }
}
