package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the Log Tail panel ({@code GET /bootui/api/log-tail/recent} and its SSE stream).
 *
 * <p>The buffer is filled by {@code MicronautLogTailAppender}. {@code /recent} answers the panel's initial
 * render from the ring buffer, and {@code /stream} replays that same backlog before following live lines, so
 * a client that opens the stream sees no gap between the two.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/log-tail")
public class LogTailController {

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final LogTailBuffer buffer;
    private final AtomicInteger openStreams = new AtomicInteger();

    public LogTailController(LogTailBuffer buffer) {
        this.buffer = buffer;
    }

    @Get("/recent")
    @Produces(MediaType.APPLICATION_JSON)
    public List<LogLineDto> recent() {
        return buffer.recent();
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<LogLineDto>> stream() {
        return SseStreams.replaying(openStreams, MAX_CONCURRENT_STREAMS, "log", listener -> {
            LogTailBuffer.Subscription subscription = buffer.subscribeWithReplay(listener);
            return new SseStreams.Subscription<>(subscription.backlog(), subscription.unsubscribe());
        });
    }
}
