package io.github.jdubois.bootui.micronaut.restclienttrace;

import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.ClientFilter;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ResponseFilter;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records the application's outbound HTTP calls into the shared engine {@link RestClientTraceRecorder},
 * which backs the REST Client panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's MicroProfile REST Client filter. Micronaut's
 * {@link ClientFilter} applies to every declarative and low-level client in the application, so one filter
 * covers them all — including clients created after startup.
 *
 * <p>Calls BootUI itself makes are excluded: the HTTP Probe panel and the vulnerability scan both issue
 * outbound requests, and a panel that showed the console's own traffic would be describing itself. Recording
 * is best-effort and never alters the request or the response.
 */
@RequiresBootUi
@Singleton
@ClientFilter("/**")
public class MicronautRestClientTraceFilter {

    /** The client type reported for every call, which is what the panel groups by. */
    static final String CLIENT_TYPE = "micronaut-http-client";

    /** Request attribute carrying the start time from the request half of the filter to the response half. */
    private static final String START_ATTRIBUTE = "bootui.rest-client-start";

    private final RestClientTraceRecorder recorder;
    private final Environment environment;

    public MicronautRestClientTraceFilter(RestClientTraceRecorder recorder, Environment environment) {
        this.recorder = recorder;
        this.environment = environment;
    }

    /**
     * Tells the recorder an instrumented client exists, so the panel can distinguish "no calls yet" from
     * "nothing is instrumented" — two states that look identical from an empty list.
     */
    @PostConstruct
    void registerInstrumentation() {
        recorder.registerClientCustomization(CLIENT_TYPE);
    }

    @RequestFilter
    public void onRequest(HttpRequest<?> request) {
        if (isSelfTraffic(request)) {
            return;
        }
        request.setAttribute(START_ATTRIBUTE, System.nanoTime());
    }

    @ResponseFilter
    public void onResponse(HttpRequest<?> request, HttpResponse<?> response) {
        Object start = request.getAttribute(START_ATTRIBUTE).orElse(null);
        if (!(start instanceof Long startNanos)) {
            return;
        }
        try {
            long durationMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            int status = response.getStatus().getCode();
            URI uri = request.getUri();
            recorder.record(
                    request.getMethodName(),
                    uri == null ? null : uri.toString(),
                    uri == null ? null : uri.getHost(),
                    uri == null ? null : uri.getPath(),
                    status,
                    durationMillis,
                    status < 400,
                    null,
                    CLIENT_TYPE,
                    headers(request),
                    Thread.currentThread().getName());
        } catch (RuntimeException ex) {
            // Capture is best-effort: it must never surface on the application's own client call.
        }
    }

    /**
     * Whether this call is BootUI's own outbound traffic — the HTTP Probe panel and the vulnerability scan
     * both make requests, and the panel describes the application, not the console.
     */
    private boolean isSelfTraffic(HttpRequest<?> request) {
        try {
            URI uri = request.getUri();
            return uri != null && MicronautBootUiPaths.isBootUiRequest(environment, uri.getPath());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static Map<String, String> headers(HttpRequest<?> request) {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            request.getHeaders().forEach((name, values) -> {
                if (values != null && !values.isEmpty()) {
                    headers.put(name, values.get(0));
                }
            });
        } catch (RuntimeException ex) {
            return Map.of();
        }
        return headers;
    }
}
