package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.github.jdubois.bootui.engine.safety.CidrRange;
import io.github.jdubois.bootui.engine.safety.ContainerGatewayDetector;
import io.github.jdubois.bootui.engine.safety.GatewayTrust;
import io.github.jdubois.bootui.engine.safety.LocalhostGuard;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardConfig;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Allow;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardDecision.Reject;
import io.github.jdubois.bootui.engine.safety.LocalhostGuardRequest;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.annotation.Order;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local-only access guard for the BootUI Micronaut console — the Micronaut analogue of the Spring
 * adapter's {@code LocalhostOnlyFilter} and of the Quarkus adapter's {@code BootUiQuarkusSafetyFilter}.
 *
 * <p>This is a <strong>thin binding</strong> over the framework-neutral {@link LocalhostGuard}: it is
 * registered as a server filter matching every path, translates the {@link HttpRequest} and the BootUI
 * configuration into a {@link LocalhostGuardRequest} / {@link LocalhostGuardConfig}, asks the guard to
 * {@link LocalhostGuard#decide decide}, and renders the {@link LocalhostGuardDecision}. The access policy
 * itself (trusted-source / Host allow-list / cross-site-write defenses, their evaluation order, and the
 * canonical 403 messages) lives in the engine, shared byte-for-byte with the Spring and Quarkus
 * adapters.</p>
 *
 * <p>The filter matches {@code /**} rather than only the console mount so it also covers requests that
 * resolve to no route at all — an unmatched {@code POST} under the BootUI surface is exactly the
 * cross-site-write case the safety contract must reject, and it must not be answered by a bare 404 that
 * skipped the guard. Scope is then narrowed in {@link #filterRequest} to the whole {@code /bootui}
 * surface (the UI and the API), mirroring the Spring adapter; cross-site-write protection additionally
 * applies to state-changing methods. The {@code micronaut.server.context-path} prefix is stripped before
 * the scope check (mirroring how the Spring filter strips the servlet context path), so the console is
 * still guarded when the host application runs under a non-default context path.</p>
 *
 * <p>The three defenses are bypassed entirely only when {@code bootui.allow-non-localhost=true}. Config is
 * read live and <em>fails closed</em> (a missing/invalid value never widens access). The container-gateway
 * snapshot is resolved once, eagerly at startup on the bean's own initialization thread (the detector does
 * blocking {@code /proc}/DNS work that must never run on an event loop), and only when
 * {@code bootui.trust-container-gateway} is not {@code OFF} so a default deployment never touches
 * {@code /proc} or DNS.</p>
 */
@RequiresBootUi
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(BootUiMicronautSafetyFilter.ORDER)
public class BootUiMicronautSafetyFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BootUiMicronautSafetyFilter.class);

    private static final String INTERNAL_BASE_PATH = MicronautBootUiPaths.INTERNAL_UI_PATH;
    private static final String INTERNAL_API_PATH = MicronautBootUiPaths.INTERNAL_API_PATH;

    static final String ALLOW_NON_LOCALHOST_KEY = "bootui.allow-non-localhost";
    static final String ALLOWED_HOSTS_KEY = "bootui.allowed-hosts";
    static final String TRUSTED_PROXIES_KEY = "bootui.trusted-proxies";
    static final String TRUST_CONTAINER_GATEWAY_KEY = "bootui.trust-container-gateway";

    /**
     * Runs before every other BootUI filter except the production shell guard. Micronaut orders filters by
     * ascending value (lowest first), the inverse of the Quarkus adapter's descending Vert.x priorities, so
     * the ordering constants here are negative mirrors of the ones there.
     */
    static final int ORDER = -1000;

    private final Environment environment;
    private final LocalhostGuard guard = new LocalhostGuard();
    private final ContainerGatewayDetector gatewayDetector;

    private volatile boolean inContainer = false;
    private volatile Set<InetAddress> containerGateways = Set.of();
    private volatile boolean loggedTrustedGateway = false;

    public BootUiMicronautSafetyFilter(Environment environment) {
        this(environment, new ContainerGatewayDetector());
    }

    BootUiMicronautSafetyFilter(Environment environment, ContainerGatewayDetector gatewayDetector) {
        this.environment = environment;
        this.gatewayDetector = gatewayDetector;
    }

    /**
     * Resolves the blocking container-gateway snapshot once, when the bean is created at startup, so no
     * request ever pays for it and it never runs on an event-loop thread.
     */
    @PostConstruct
    void initialize() {
        resolveGatewaySnapshot();
    }

    /**
     * Applies the guard, returning a rendered 403 to short-circuit the request or {@code null} to let it
     * proceed.
     */
    @RequestFilter
    @Nullable
    public HttpResponse<?> filterRequest(HttpRequest<?> request) {
        String relativePath = bootUiRelativePath(request.getPath());
        if (!isBootUiRequest(relativePath)) {
            return null;
        }

        LocalhostGuardDecision decision = guard.decide(toGuardRequest(request), buildConfig());

        if (decision instanceof Reject reject) {
            logRejection(reject, request);
            return applySecurityHeaders(reject(reject.message()), relativePath);
        }

        if (decision instanceof Allow allow && allow.trustedViaGateway()) {
            warnTrustedGatewayOnce(allow.trustedGateway());
        }
        return null;
    }

    /**
     * Applies the BootUI security-header policy to every response on the console surface, so both passing
     * and rejected responses are covered.
     */
    @ResponseFilter
    public void filterResponse(HttpRequest<?> request, MutableHttpResponse<?> response) {
        String relativePath = bootUiRelativePath(request.getPath());
        if (!isBootUiRequest(relativePath)) {
            return;
        }
        applySecurityHeaders(response, relativePath);
    }

    private <T> MutableHttpResponse<T> applySecurityHeaders(MutableHttpResponse<T> response, String relativePath) {
        int statusCode = response.getStatus().getCode();
        String apiPath = apiPathFor(relativePath);
        if (BootUiSecurityHeaders.removesPragma(relativePath, apiPath, statusCode)) {
            response.getHeaders().remove(BootUiSecurityHeaders.PRAGMA);
        }
        BootUiSecurityHeaders.headersFor(relativePath, apiPath, statusCode).forEach((name, value) -> {
            if (BootUiSecurityHeaders.overridesExisting(name)
                    || !response.getHeaders().contains(name)) {
                response.getHeaders().set(name, value);
            }
        });
        return response;
    }

    /**
     * Returns {@code true} for the BootUI UI and API surface, using the same strict boundary check as the
     * Spring and Quarkus adapters (an exact match or a {@code /}-delimited sub-path) so an unrelated path
     * such as {@code /bootui-other} is not caught.
     *
     * <p>Both the configured mounts ({@code bootui.path} / {@code bootui.api-path}, where the controllers
     * actually live on Micronaut) and the reserved internal {@code /bootui} mount (where the packaged SPA
     * assets live) are guarded, so moving the console with {@code bootui.path} can never leave either
     * surface unguarded. The paths are read live and fail closed to the internal mounts.
     *
     * @param relativePath the request path with the server context path already stripped
     */
    boolean isBootUiRequest(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        return MicronautBootUiPaths.isSameOrChild(relativePath, INTERNAL_BASE_PATH)
                || MicronautBootUiPaths.isSameOrChild(relativePath, INTERNAL_API_PATH)
                || MicronautBootUiPaths.isSameOrChild(relativePath, MicronautBootUiPaths.safeUiPath(environment))
                || MicronautBootUiPaths.isSameOrChild(relativePath, MicronautBootUiPaths.safeApiPath(environment));
    }

    /**
     * The API mount to evaluate this path against for the cache-control policy: the configured one for a
     * request under it, the reserved internal one otherwise (which is where the packaged SPA assets live).
     */
    private String apiPathFor(String relativePath) {
        String configuredApiPath = MicronautBootUiPaths.safeApiPath(environment);
        return MicronautBootUiPaths.isSameOrChild(relativePath, configuredApiPath)
                ? configuredApiPath
                : INTERNAL_API_PATH;
    }

    /**
     * Removes the configured {@code micronaut.server.context-path} prefix from the request path so the
     * BootUI scope check is context-path-relative. Without stripping, {@link #isBootUiRequest} would not
     * recognize the prefixed path and the guard would be skipped (fail-open). The context path is read live
     * and <em>fails closed</em>: a missing/blank value normalizes to {@code ""} (no prefix), which still
     * guards the default {@code /bootui} surface.
     */
    String bootUiRelativePath(String path) {
        return MicronautContextPath.stripPrefix(path, MicronautContextPath.normalize(contextPath()));
    }

    private String contextPath() {
        return environment
                .getProperty(MicronautContextPath.CONTEXT_PATH_KEY, String.class)
                .orElse("/");
    }

    /**
     * Builds the neutral guard request from the Micronaut request, passing <em>raw</em> values: the guard
     * owns all parsing. The source address is always the real TCP peer, never a forwarded header.
     */
    LocalhostGuardRequest toGuardRequest(HttpRequest<?> request) {
        return new LocalhostGuardRequest(
                request.getMethodName(),
                remoteAddr(request.getRemoteAddress()),
                hostAuthority(request),
                request.getHeaders().get("Origin"),
                request.getHeaders().get("Sec-Fetch-Site"));
    }

    /**
     * Sources the host authority for the Host allow-list. The raw {@code Host} header is preferred so the
     * parse is byte-identical to the Spring and Quarkus adapters for the common HTTP/1.1 case; when no
     * {@code Host} header is present (HTTP/2 carries it in the {@code :authority} pseudo-header, which
     * Micronaut surfaces on the request URI) the URI authority is used so the allow-list still applies.
     * Returns {@code null} when neither is present, which the guard treats as a missing Host (allowed for
     * non-browser local clients); a present but malformed {@code Host} header is passed through unchanged
     * so the guard can reject it.
     */
    @Nullable
    static String hostAuthority(HttpRequest<?> request) {
        String hostHeader = request.getHeaders().get("Host");
        if (hostHeader != null && !hostHeader.isBlank()) {
            return hostHeader;
        }
        String authority = request.getUri().getAuthority();
        return authority == null || authority.isBlank() ? hostHeader : authority;
    }

    /** The raw socket peer address (e.g. {@code 127.0.0.1}), or {@code null} when unavailable. */
    static String remoteAddr(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        InetAddress address = remoteAddress.getAddress();
        return address == null ? remoteAddress.getHostString() : address.getHostAddress();
    }

    /**
     * Returns whether {@code remoteAddr} is a genuinely trusted source (loopback, a configured trusted
     * range, or a trusted container gateway) under the same {@link LocalhostGuard} policy and once-resolved
     * gateway snapshot this filter uses — <em>not</em> whether {@code bootui.allow-non-localhost} merely
     * bypassed the check. Reused by {@link BootUiMicronautAuthenticationFilter} so bearer-token
     * authentication treats an already-trusted source exactly as frictionlessly as this filter does,
     * instead of re-deriving a narrower, drift-prone notion of "local".
     */
    boolean isTrustedSource(String remoteAddr) {
        return guard.isTrustedSource(remoteAddr, buildConfig());
    }

    /**
     * Builds the per-request guard configuration from live config plus the once-resolved gateway snapshot.
     * When {@code bootui.trust-container-gateway} is {@code OFF} (the default) the snapshot is empty and
     * the guard never trusts a gateway.
     */
    private LocalhostGuardConfig buildConfig() {
        GatewayTrust gatewayTrust = gatewayTrust();
        boolean container;
        Set<InetAddress> gateways;
        if (gatewayTrust == GatewayTrust.OFF) {
            container = false;
            gateways = Set.of();
        } else {
            container = this.inContainer;
            gateways = this.containerGateways;
        }
        return new LocalhostGuardConfig(
                allowNonLocalhost(), allowedHosts(), trustedRanges(), gatewayTrust, container, gateways);
    }

    private boolean allowNonLocalhost() {
        return BootUiBooleans.value(environment, ALLOW_NON_LOCALHOST_KEY, false, LOG);
    }

    private List<String> allowedHosts() {
        return stringList(ALLOWED_HOSTS_KEY);
    }

    private List<CidrRange> trustedRanges() {
        List<CidrRange> parsed = new ArrayList<>();
        for (String entry : stringList(TRUSTED_PROXIES_KEY)) {
            CidrRange range = CidrRange.parse(entry);
            if (range != null) {
                parsed.add(range);
            } else if (entry != null && !entry.isBlank()) {
                LOG.warn("BootUI ignoring malformed {} entry '{}'", TRUSTED_PROXIES_KEY, entry);
            }
        }
        return List.copyOf(parsed);
    }

    /**
     * Reads a list-valued property, accepting both the YAML list form and a single comma-separated string
     * (which is how the same key is written on the command line or in a {@code .properties} file).
     */
    private List<String> stringList(String key) {
        List<String> values =
                environment.getProperty(key, Argument.listOf(String.class)).orElse(null);
        if (values != null) {
            return values.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String raw = environment.getProperty(key, String.class).orElse(null);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    /**
     * Reads {@code bootui.trust-container-gateway} and maps it onto the neutral {@link GatewayTrust}. A
     * missing, blank, or invalid value fails closed to {@link GatewayTrust#OFF}.
     */
    private GatewayTrust gatewayTrust() {
        String raw = environment
                .getProperty(TRUST_CONTAINER_GATEWAY_KEY, String.class)
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return GatewayTrust.OFF;
        }
        try {
            return GatewayTrust.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warn(
                    "Ignoring invalid BootUI property '{}={}'; falling back to OFF.", TRUST_CONTAINER_GATEWAY_KEY, raw);
            return GatewayTrust.OFF;
        }
    }

    /**
     * Resolves and caches the container detection result and trusted gateways exactly once, eagerly at
     * startup. Skipped entirely (no {@code /proc}/DNS access) when gateway trust is {@code OFF}. The
     * trusted set is the union of the route-table default gateway and any Docker Desktop gateway. The
     * detector already fails soft; this additionally guards against any unexpected error, leaving the
     * snapshot empty (so gateway trust is never granted) on failure.
     */
    void resolveGatewaySnapshot() {
        if (gatewayTrust() == GatewayTrust.OFF) {
            return;
        }
        try {
            this.inContainer = gatewayDetector.isInContainer();
            Set<InetAddress> gateways = new LinkedHashSet<>();
            gatewayDetector.defaultGateway().ifPresent(gateways::add);
            gateways.addAll(gatewayDetector.dockerDesktopGateways());
            this.containerGateways = Set.copyOf(gateways);
        } catch (RuntimeException ex) {
            LOG.warn("BootUI container-gateway detection failed; not trusting any gateway.", ex);
            this.inContainer = false;
            this.containerGateways = Set.of();
        }
    }

    private void warnTrustedGatewayOnce(InetAddress gateway) {
        if (loggedTrustedGateway) {
            return;
        }
        loggedTrustedGateway = true;
        LOG.warn(
                "BootUI trusting auto-detected container gateway {} (/32) for loopback-equivalent access.",
                gateway != null ? gateway.getHostAddress() : null);
    }

    private void logRejection(Reject reject, HttpRequest<?> request) {
        String path = request.getPath();
        switch (reject.reason()) {
            case NON_LOOPBACK_SOURCE ->
                LOG.warn(
                        "BootUI rejected non-loopback request from {} to {}",
                        remoteAddr(request.getRemoteAddress()),
                        path);
            case DISALLOWED_HOST ->
                LOG.warn(
                        "BootUI rejected request with disallowed Host '{}' to {}",
                        request.getHeaders().get("Host"),
                        path);
            case CROSS_SITE_WRITE ->
                LOG.warn("BootUI rejected cross-site {} request to {}", request.getMethodName(), path);
        }
    }

    private MutableHttpResponse<String> reject(String message) {
        return HttpResponse.status(io.micronaut.http.HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .body("{\"error\":\"" + message + "\"}");
    }
}
