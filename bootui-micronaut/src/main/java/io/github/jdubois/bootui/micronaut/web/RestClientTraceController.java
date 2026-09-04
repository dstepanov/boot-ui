package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the REST Client panel ({@code GET /bootui/api/rest-client-trace}, the clear and recording
 * actions, and the SSE stream).
 *
 * <p>A thin transport adapter over the shared engine {@link RestClientTraceRecorder}, filled by
 * {@code MicronautRestClientTraceFilter}. The panel distinguishes "recording is off", "no client is
 * instrumented" and "no calls yet" rather than showing one empty list for all three.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/rest-client-trace")
public class RestClientTraceController {

    static final String DISABLED_REASON =
            "REST client tracing is disabled (set bootui.rest-client-trace.enabled=true in a trusted local"
                    + " profile).";

    static final String NOT_INSTRUMENTED_REASON =
            "No Micronaut HTTP client has been instrumented yet. Calls appear once the application makes one"
                    + " through a declarative @Client or the low-level HttpClient.";

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final RestClientTraceRecorder recorder;
    private final MicronautExposurePolicy exposure;
    private final AtomicInteger openStreams = new AtomicInteger();

    public RestClientTraceController(RestClientTraceRecorder recorder, MicronautExposurePolicy exposure) {
        this.recorder = recorder;
        this.exposure = exposure;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport trace() {
        return report();
    }

    @Post("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport clear() {
        recorder.clear();
        return report();
    }

    @Post("/recording")
    @Produces(MediaType.APPLICATION_JSON)
    public RestClientTraceReport recording(@Body @Nullable RestClientTraceRecordingRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report();
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(openStreams, MAX_CONCURRENT_STREAMS, recorder::subscribe);
    }

    private RestClientTraceReport report() {
        if (!recorder.isEnabled()) {
            return RestClientTraceReport.unavailable(DISABLED_REASON);
        }
        if (!recorder.hasInstrumentedClient()) {
            return RestClientTraceReport.unavailable(NOT_INSTRUMENTED_REASON);
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure());
    }
}
