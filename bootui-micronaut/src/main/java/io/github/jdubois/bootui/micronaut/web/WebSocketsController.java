package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.WebSocketCaptureRequest;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.websocket.WebSocketActivityRecorder;
import io.github.jdubois.bootui.engine.websocket.WebSocketService;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the WebSockets panel ({@code GET /bootui/api/websockets}, the clear and capture actions,
 * and the SSE stream).
 *
 * <p>A thin transport adapter over the shared engine {@link WebSocketService}. The panel's availability is
 * consulted before every answer so a report is never fabricated for an application that has no WebSocket
 * support on its classpath — it reports the same reason the manifest gives.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/websockets")
public class WebSocketsController {

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final WebSocketService service;
    private final WebSocketActivityRecorder recorder;
    private final MicronautPanelAvailability panelAvailability;
    private final AtomicInteger openStreams = new AtomicInteger();

    public WebSocketsController(
            WebSocketService service,
            WebSocketActivityRecorder recorder,
            MicronautPanelAvailability panelAvailability) {
        this.service = service;
        this.recorder = recorder;
        this.panelAvailability = panelAvailability;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport report() {
        return snapshot();
    }

    @Delete
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport clear() {
        service.clear();
        return snapshot();
    }

    @Post("/capture")
    @Produces(MediaType.APPLICATION_JSON)
    public WebSocketReport capture(@Body @Nullable WebSocketCaptureRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isCapturing() : request.enabled();
        recorder.setCapturing(enabled);
        return snapshot();
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(openStreams, MAX_CONCURRENT_STREAMS, recorder::subscribe);
    }

    private WebSocketReport snapshot() {
        if (!panelAvailability.isPanelAvailable(BootUiPanels.WEBSOCKETS)) {
            return WebSocketReport.unavailable(panelAvailability.panelUnavailableReason(BootUiPanels.WEBSOCKETS));
        }
        return service.report();
    }
}
