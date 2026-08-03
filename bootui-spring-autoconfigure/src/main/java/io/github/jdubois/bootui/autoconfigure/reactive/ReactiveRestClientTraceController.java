package io.github.jdubois.bootui.autoconfigure.reactive;

import io.github.jdubois.bootui.autoconfigure.config.BootUiExposure;
import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.RestClientTraceReport;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Reactive (WebFlux) sibling of {@code RestClientTraceController}: identical
 * read/clear/recording semantics over the same framework-neutral
 * {@link RestClientTraceRecorder}, with the {@code /stream} endpoint rebuilt on
 * {@link ReactiveBootUiChangeStream} instead of the servlet-only {@code SseEmitter}.
 *
 * <p>On WebFlux only {@code WebClient} calls are captured (via the auto-configured
 * {@code WebClientCustomizer} in {@code BootUiEngineConfiguration}); the
 * {@code RestClient}/{@code RestTemplate} interceptors are servlet-side concerns and are
 * gated by their own {@code @ConditionalOnClass(RestClientCustomizer)} — they are never
 * linked on a genuinely WebFlux-only classpath, so the recorder's
 * {@link RestClientTraceRecorder#hasInstrumentedClient()} reliably reflects only the
 * clients actually present and customized.</p>
 */
@RestController
@RequestMapping("/bootui/api/rest-client-trace")
public class ReactiveRestClientTraceController {

    private static final String NOT_CONFIGURED = "REST client tracing is not configured";

    private final ObjectProvider<RestClientTraceRecorder> recorderProvider;
    private final BootUiExposure exposure;
    private final ReactiveBootUiChangeStream changeStream;
    private Runnable recorderUnsubscribe;

    public ReactiveRestClientTraceController(
            ObjectProvider<RestClientTraceRecorder> recorderProvider, BootUiExposure exposure) {
        this.recorderProvider = recorderProvider;
        this.exposure = exposure;
        this.changeStream = new ReactiveBootUiChangeStream("rest-client-trace");
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder != null) {
            this.recorderUnsubscribe = recorder.subscribe(changeStream::signal);
        }
    }

    /**
     * Completes any open SSE streams and detaches the recorder listener when the context starts
     * closing. See {@code SqlTraceController#shutdown} for why this runs on
     * {@link ContextClosedEvent} rather than a destroy callback.
     */
    @EventListener(ContextClosedEvent.class)
    void shutdown() {
        if (recorderUnsubscribe != null) {
            recorderUnsubscribe.run();
            recorderUnsubscribe = null;
        }
        changeStream.close();
    }

    @GetMapping
    public RestClientTraceReport trace() {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return RestClientTraceReport.unavailable(NOT_CONFIGURED);
        }
        return report(recorder);
    }

    @PostMapping("/clear")
    public RestClientTraceReport clear() {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return RestClientTraceReport.unavailable(NOT_CONFIGURED);
        }
        recorder.clear();
        return report(recorder);
    }

    @PostMapping("/recording")
    public RestClientTraceReport recording(
            @RequestBody(required = false) RestClientTraceRecordingRequest request) {
        RestClientTraceRecorder recorder = recorderProvider.getIfAvailable();
        if (recorder == null) {
            return RestClientTraceReport.unavailable(NOT_CONFIGURED);
        }
        boolean enabled =
                (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report(recorder);
    }

    /**
     * Streams a coalesced {@code update} notification whenever a call is captured, the buffer is
     * cleared, or recording is paused/resumed, so the browser can refresh live without polling.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream() {
        return changeStream.open();
    }

    private RestClientTraceReport report(RestClientTraceRecorder recorder) {
        if (!recorder.hasInstrumentedClient()) {
            return RestClientTraceReport.unavailable(unavailableReason(recorder));
        }
        return recorder.report(exposure.maskSecrets(), exposure.valueExposure());
    }

    private String unavailableReason(RestClientTraceRecorder recorder) {
        if (!recorder.isEnabled()) {
            return "REST client tracing is disabled (set bootui.rest-client-trace.enabled=true in a "
                    + "trusted local profile).";
        }
        return "No RestClient, RestTemplate, or WebClient has been instrumented yet.";
    }
}
