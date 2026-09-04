package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.SecurityLogsReport;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.support.BlankStrings;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.security.MicronautSecurityPresence;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the Security Logs panel ({@code GET /bootui/api/security-logs} and its SSE stream).
 *
 * <p>A thin transport adapter over the shared engine {@link SecurityLogsService}, which masks and pages the
 * captured events. The capture lives in {@code MicronautSecurityEventCapture} and exists only when
 * {@code micronaut-security} is present; without it the panel reports that honestly rather than showing an
 * empty list that could be mistaken for "no security events".
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/security-logs")
public class SecurityLogsController {

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    static final String SECURITY_ABSENT_REASON =
            "Micronaut Security is not on the classpath, so there are no authentication or authorization"
                    + " events to capture. Add micronaut-security to record them.";

    private final SecurityEventBuffer buffer;
    private final MicronautExposurePolicy exposure;
    private final Environment environment;
    private final SecurityLogsService service = new SecurityLogsService();
    private final AtomicInteger openStreams = new AtomicInteger();

    public SecurityLogsController(
            SecurityEventBuffer buffer, MicronautExposurePolicy exposure, Environment environment) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.environment = environment;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> logs(
            @QueryValue @Nullable String principal,
            @QueryValue @Nullable String type,
            @QueryValue @Nullable String after,
            @QueryValue @Nullable Integer offset,
            @QueryValue @Nullable Integer limit) {
        int maxLogs = service.maxLogs(environment
                .getProperty("bootui.security-logs.max-logs", Integer.class)
                .orElse(500));
        if (!MicronautSecurityPresence.available()) {
            return HttpResponse.ok(SecurityLogsReport.unavailable(SECURITY_ABSENT_REASON, maxLogs));
        }
        Instant parsedAfter;
        try {
            parsedAfter = BlankStrings.parseInstant(after);
        } catch (DateTimeException ex) {
            // Mirror the Spring controller: a malformed `after` is a 400, not a 500.
            return HttpResponse.badRequest(
                    Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
        }
        return HttpResponse.ok(service.report(
                buffer.snapshot(),
                maxLogs,
                exposure.maskSecrets(),
                exposure.valueExposure(),
                BlankStrings.blankToNullTrimmed(principal),
                BlankStrings.blankToNullTrimmed(type),
                parsedAfter,
                offset,
                limit));
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(openStreams, MAX_CONCURRENT_STREAMS, buffer::subscribe);
    }
}
