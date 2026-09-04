package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Order;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import io.micronaut.http.annotation.ServerFilter;
import jakarta.inject.Singleton;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records every application HTTP exchange into the shared engine {@link HttpExchangeBuffer}, which backs
 * the HTTP Exchanges and Live Activity panels.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusHttpExchangeCaptureFilter}. Micronaut
 * has no Actuator-style exchange repository, so — as on Quarkus — a filter is the capture source. It only
 * records: it never short-circuits, never mutates the exchange, and swallows its own failures, so a capture
 * problem can never affect the application's traffic.
 *
 * <p>BootUI's own console traffic is excluded through the shared
 * {@link MicronautBootUiPaths#isBootUiRequest} matcher — the same one every other self-traffic exclusion in
 * this adapter uses — so the panel can never fill up with the console watching itself.
 *
 * <p>Bodies are deliberately not captured: reading them would require buffering the application's request
 * and response payloads, which changes streaming behavior and memory profile. The panel shows the exchange
 * envelope (method, URI, status, duration, headers, principal, trace id), with headers masked by the engine
 * behind the live exposure policy.
 */
@RequiresBootUi
@Singleton
@ServerFilter(ServerFilter.MATCH_ALL_PATTERN)
@Order(MicronautHttpExchangeCaptureFilter.ORDER)
public class MicronautHttpExchangeCaptureFilter {

    /** Runs after the access filters, which may legitimately short-circuit a request before it is recorded. */
    static final int ORDER = -900;

    /** Request attribute holding the capture state between the request and response halves of the filter. */
    private static final String STATE_ATTRIBUTE = "bootui.exchange-capture";

    private final HttpExchangeBuffer buffer;
    private final Environment environment;
    private final BeanContext beanContext;

    public MicronautHttpExchangeCaptureFilter(
            HttpExchangeBuffer buffer, Environment environment, BeanContext beanContext) {
        this.buffer = buffer;
        this.environment = environment;
        this.beanContext = beanContext;
    }

    @RequestFilter
    public void onRequest(HttpRequest<?> request) {
        if (MicronautBootUiPaths.isBootUiRequest(environment, request.getPath())) {
            return;
        }
        request.setAttribute(
                STATE_ATTRIBUTE,
                new CaptureState(
                        Instant.now(),
                        System.nanoTime(),
                        headers(request.getHeaders().asMap()),
                        traceId()));
    }

    @ResponseFilter
    public void onResponse(HttpRequest<?> request, HttpResponse<?> response) {
        Object state = request.getAttribute(STATE_ATTRIBUTE).orElse(null);
        if (!(state instanceof CaptureState captured)) {
            return;
        }
        try {
            long durationMs = (System.nanoTime() - captured.startNanos()) / 1_000_000L;
            buffer.record(new CapturedHttpExchange(
                    captured.started(),
                    request.getMethodName(),
                    uriOf(request),
                    response.getStatus().getCode(),
                    durationMs,
                    remoteAddress(request),
                    principal(request),
                    null,
                    captured.requestHeaders(),
                    headers(response.getHeaders().asMap()),
                    captured.traceId()));
        } catch (RuntimeException ex) {
            // Capture is best-effort: a recording failure must never surface on the application's response.
        }
    }

    /**
     * The current trace id, when the application contributes a {@link TraceIdProvider} (Micronaut Tracing).
     * Resolved per request rather than cached so an application that adds tracing later is picked up, and
     * failing soft to {@code null} so an exchange is still recorded without correlation.
     */
    private String traceId() {
        try {
            return beanContext
                    .findBean(TraceIdProvider.class)
                    .map(TraceIdProvider::currentTraceId)
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static URI uriOf(HttpRequest<?> request) {
        try {
            return request.getUri();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String remoteAddress(HttpRequest<?> request) {
        try {
            var address = request.getRemoteAddress();
            if (address == null) {
                return null;
            }
            return address.getAddress() == null
                    ? address.getHostString()
                    : address.getAddress().getHostAddress();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String principal(HttpRequest<?> request) {
        try {
            return request.getUserPrincipal().map(Principal::getName).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Map<String, List<String>> headers(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        headers.forEach((name, values) -> copy.put(name, values == null ? List.of() : new ArrayList<>(values)));
        return copy;
    }

    /** The per-request capture state carried from the request half of the filter to the response half. */
    private record CaptureState(
            Instant started, long startNanos, Map<String, List<String>> requestHeaders, String traceId) {}
}
