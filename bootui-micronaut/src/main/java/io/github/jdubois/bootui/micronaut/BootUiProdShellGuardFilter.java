package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;

/**
 * Keeps the configured BootUI surface and its private {@code /bootui} mount dark whenever the console is
 * not active, including the part that is reachable for a reason the other filters cannot fix.
 *
 * <p>When {@link BootUiEnabledCondition} does not match, none of BootUI's controllers, filters or services
 * is created, so every data-bearing {@code /bootui/api/**} endpoint is already unreachable. The shared Vue
 * bundle under {@code META-INF/resources/bootui/}, however, is a plain classpath resource: any application
 * that has configured a matching {@code micronaut.router.static-resources} mapping — including the one
 * this adapter registers itself — would still serve the empty SPA shell. This filter turns that into a
 * plain 404, at parity with the Spring adapter, where {@code BootUiShellGuardAutoConfiguration} answers the
 * same 404 for the same reserved mount, and with the Quarkus adapter's {@code BootUiProdShellGuardFilter}.
 *
 * <p>This bean is deliberately <strong>always registered</strong> — it is the one BootUI bean that carries
 * no {@link RequiresBootUi} gate. The activation decision is made once, at construction, and stored in
 * {@link #consoleActive}; {@link #filterRequest} is an immediate pass-through whenever the console is
 * active, so development and test behavior (including everything the shared conformance suite exercises)
 * is entirely unaffected. This single, easy-to-audit check is the reason the security decision lives inside
 * the filter rather than in the bean condition: it cannot be defeated by getting a condition's polarity
 * backwards, since there is no alternate polarity here at all — the bean is unconditionally present, and
 * only its runtime behavior changes.
 *
 * <p>It suppresses the normalized configured UI/API paths as well as the fixed classpath mount, while
 * invalid dormant configuration falls back to safe defaults rather than activating any console route. The
 * {@code micronaut.server.context-path} prefix is stripped before matching (shared
 * {@link MicronautContextPath} helper), so a host application running under a non-default context path is
 * still fully covered.
 */
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(BootUiProdShellGuardFilter.ORDER)
public class BootUiProdShellGuardFilter {

    /** Internal classpath path — always {@code /bootui}; the compiled SPA assets live here. */
    static final String INTERNAL_PATH = "/bootui";

    /** Runs before every other BootUI filter, so a dark console is never even evaluated further. */
    static final int ORDER = -2000;

    private final boolean consoleActive;
    private final String configuredPath;
    private final String configuredApiPath;
    private final String contextPrefix;

    public BootUiProdShellGuardFilter(Environment environment) {
        this.consoleActive =
                BootUiMicronautActivationResolver.resolve(environment).enabled();
        this.configuredPath = MicronautBootUiPaths.safeUiPath(environment);
        this.configuredApiPath = MicronautBootUiPaths.safeApiPath(environment);
        this.contextPrefix = MicronautBootUiPaths.contextPrefix(environment);
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filterRequest(HttpRequest<?> request) {
        if (consoleActive) {
            // The console is meant to be fully reachable, so never touch the request.
            return null;
        }

        String path = request.getPath();
        if (path == null) {
            return null;
        }

        String relativePath = MicronautContextPath.stripPrefix(path, contextPrefix);
        if (!isBootUiPath(relativePath, configuredPath, configuredApiPath)) {
            return null;
        }

        // Determine the API path for cache-control header differentiation: use the configuredApiPath for
        // requests at the configured path, the internal API path for direct internal-path access.
        String internalApiPath = INTERNAL_PATH + "/api";
        String apiPath = relativePath.equals(configuredApiPath) || relativePath.startsWith(configuredApiPath + "/")
                ? configuredApiPath
                : internalApiPath;

        MutableHttpResponse<?> response = HttpResponse.status(HttpStatus.NOT_FOUND);
        if (BootUiSecurityHeaders.removesPragma(relativePath, apiPath, 404)) {
            response.getHeaders().remove(BootUiSecurityHeaders.PRAGMA);
        }
        BootUiSecurityHeaders.headersFor(relativePath, apiPath, 404).forEach((name, value) -> {
            if (BootUiSecurityHeaders.overridesExisting(name)
                    || !response.getHeaders().contains(name)) {
                response.getHeaders().set(name, value);
            }
        });
        return response;
    }

    /**
     * Returns {@code true} for the whole BootUI surface under both the configured base path and the
     * internal classpath path ({@code /bootui}), so the static Vue assets at their classpath location are
     * suppressed even when a custom {@code bootui.path} is configured.
     */
    static boolean isBootUiPath(String path, String configuredPath, String configuredApiPath) {
        if (path == null) {
            return false;
        }
        return path.equals(configuredPath)
                || path.startsWith(configuredPath + "/")
                || path.equals(configuredApiPath)
                || path.startsWith(configuredApiPath + "/")
                || path.equals(INTERNAL_PATH)
                || path.startsWith(INTERNAL_PATH + "/");
    }

    static boolean isBootUiPath(String path, String configuredPath) {
        return isBootUiPath(path, configuredPath, configuredPath + "/api");
    }
}
