package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.safety.ApiTokenAuthenticator;
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

/**
 * Authenticates non-loopback requests to the BootUI API — the Micronaut analogue of the Quarkus adapter's
 * {@code BootUiQuarkusAuthenticationFilter}.
 *
 * <p>Trust is delegated to {@link BootUiMicronautSafetyFilter#isTrustedSource(String)} rather than
 * re-derived here: a source already trusted via loopback, {@code bootui.trusted-proxies}, or
 * {@code bootui.trust-container-gateway} is treated identically, so an operator who already opted into one
 * of those trust mechanisms keeps frictionless access instead of also being forced through the
 * bearer-token/unlock flow. Everyone else must present the token, which BootUI generates at startup and
 * logs once when remote access is configured.
 *
 * <p>It runs after the safety guard and before panel gating, so a request that is rejected outright is
 * never given an authentication challenge that might suggest the surface is reachable.
 */
@RequiresBootUi
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(BootUiMicronautAuthenticationFilter.ORDER)
public class BootUiMicronautAuthenticationFilter {

    /** Runs between the safety filter and panel gating, matching the Quarkus adapter's ordering. */
    static final int ORDER = -975;

    /** The API sub-path the shared UI unlocks a browser session through. */
    private static final String SESSION_SUFFIX = "/auth/session";

    private final Environment environment;
    private final ApiTokenAuthenticator authenticator;
    private final BootUiMicronautSafetyFilter safetyFilter;

    public BootUiMicronautAuthenticationFilter(
            Environment environment, ApiTokenAuthenticator authenticator, BootUiMicronautSafetyFilter safetyFilter) {
        this.environment = environment;
        this.authenticator = authenticator;
        this.safetyFilter = safetyFilter;
    }

    @RequestFilter
    @Nullable
    public HttpResponse<?> filterRequest(HttpRequest<?> request) {
        String path = relativePath(request.getPath());
        String apiPath = MicronautBootUiPaths.safeApiPath(environment);
        if (!MicronautBootUiPaths.isSameOrChild(path, apiPath)) {
            return null;
        }

        String remoteAddress = BootUiMicronautSafetyFilter.remoteAddr(request.getRemoteAddress());
        boolean trustedSource = safetyFilter.isTrustedSource(remoteAddress);
        if (!authenticator.isAuthorized(
                trustedSource,
                request.getHeaders().get("Authorization"),
                request.getHeaders().get("Cookie"))) {
            return reject();
        }

        if ("POST".equals(request.getMethodName()) && (apiPath + SESSION_SUFFIX).equals(path)) {
            var session = HttpResponse.status(HttpStatus.NO_CONTENT);
            if (!trustedSource) {
                session = session.header("Set-Cookie", sessionCookie(request));
            }
            return session;
        }

        return null;
    }

    /**
     * The browser session cookie handed to an authenticated non-trusted caller, scoped to the console's own
     * API mount and marked {@code Secure} only when the request itself arrived over TLS.
     */
    private String sessionCookie(HttpRequest<?> request) {
        return ApiTokenAuthenticator.SESSION_COOKIE_NAME
                + "="
                + authenticator.token()
                + "; Path="
                + MicronautBootUiPaths.applicationApiPath(environment)
                + "; HttpOnly; SameSite=Strict"
                + (request.isSecure() ? "; Secure" : "");
    }

    private String relativePath(String path) {
        return MicronautContextPath.stripPrefix(path, MicronautBootUiPaths.contextPrefix(environment));
    }

    private static HttpResponse<String> reject() {
        return HttpResponse.status(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store")
                .header("WWW-Authenticate", ApiTokenAuthenticator.AUTHENTICATION_CHALLENGE)
                .body("{\"error\":\"" + ApiTokenAuthenticator.AUTHENTICATION_REQUIRED_MESSAGE + "\"}");
    }
}
