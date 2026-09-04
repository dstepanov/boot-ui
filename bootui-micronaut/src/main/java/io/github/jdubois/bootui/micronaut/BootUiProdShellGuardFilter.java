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
 * Keeps the BootUI console surface dark whenever the console is not active, including the part that would
 * otherwise stay reachable for a reason the other filters cannot fix.
 *
 * <p>When {@link BootUiEnabledCondition} does not match, none of BootUI's controllers, filters or services
 * is created, so every {@code bootui.api-path} endpoint and the SPA shell itself are already unreachable.
 * The compiled Vue bundle inside {@code bootui-ui}, however, is a plain classpath resource under
 * {@code META-INF/resources/bootui/}: any application that has configured a
 * {@code micronaut.router.static-resources} mapping over {@code classpath:META-INF/resources} — a common
 * Micronaut setup, and not something BootUI registers or controls — would still serve the empty SPA shell
 * from the console's mount. This filter turns that into a plain 404, at parity with the Spring adapter,
 * where {@code BootUiShellGuardAutoConfiguration} answers the same 404, and with the Quarkus adapter's
 * {@code BootUiProdShellGuardFilter}.
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
 * <p>The suppressed surface is exactly the console's own dormant mounts — the normalized {@code bootui.path}
 * and {@code bootui.api-path}, with invalid configuration falling back to the defaults rather than
 * activating any console route. It is deliberately not any wider than that: on Micronaut the console
 * occupies only its configured mounts, so claiming a fixed {@code /bootui} on top of them would blank out
 * an application's own routes at that path in production the moment an operator had moved the console
 * elsewhere. The {@code micronaut.server.context-path} prefix is stripped before matching (shared
 * {@link MicronautContextPath} helper), so a host application running under a non-default context path is
 * still fully covered.
 */
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(BootUiProdShellGuardFilter.ORDER)
public class BootUiProdShellGuardFilter {

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

        MutableHttpResponse<?> response = HttpResponse.status(HttpStatus.NOT_FOUND);
        if (BootUiSecurityHeaders.removesPragma(relativePath, configuredApiPath, 404)) {
            response.getHeaders().remove(BootUiSecurityHeaders.PRAGMA);
        }
        BootUiSecurityHeaders.headersFor(relativePath, configuredApiPath, 404).forEach((name, value) -> {
            if (BootUiSecurityHeaders.overridesExisting(name)
                    || !response.getHeaders().contains(name)) {
                response.getHeaders().set(name, value);
            }
        });
        return response;
    }

    /**
     * Returns {@code true} for the console's dormant UI and API mounts, and for nothing else — a strict
     * {@code /}-delimited boundary check, so a neighbouring application route such as {@code /bootui-other}
     * is never blanked out.
     */
    static boolean isBootUiPath(String path, String configuredPath, String configuredApiPath) {
        return MicronautBootUiPaths.isSameOrChild(path, configuredPath)
                || MicronautBootUiPaths.isSameOrChild(path, configuredApiPath);
    }

    static boolean isBootUiPath(String path, String configuredPath) {
        return isBootUiPath(path, configuredPath, configuredPath + "/api");
    }
}
