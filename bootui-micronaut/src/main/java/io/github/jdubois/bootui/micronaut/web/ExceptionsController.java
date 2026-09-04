package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.ExceptionStatusUpdateRequest;
import io.github.jdubois.bootui.core.dto.ExceptionsReport;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the Exceptions panel ({@code GET /bootui/api/exceptions}, its detail, clear, status and
 * stream endpoints).
 *
 * <p>A thin transport adapter over the shared engine {@link ExceptionStore} and {@link ExceptionsService},
 * which own grouping, fingerprinting, masking and the triage status model. The capture lives in
 * {@code MicronautExceptionLogAppender}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/exceptions")
public class ExceptionsController {

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final ExceptionStore store;
    private final ExceptionsService service;
    private final AtomicInteger openStreams = new AtomicInteger();

    public ExceptionsController(ExceptionStore store, ExceptionsService service) {
        this.store = store;
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ExceptionsReport list() {
        return service.report(store);
    }

    /**
     * The detail of one exception group.
     *
     * <p>An unknown id is reported by <em>throwing</em> rather than by returning a bare
     * {@code HttpResponse.notFound()}: the same method backs the {@code get_exception_detail} MCP/CLI tool,
     * and {@code MicronautMcpToolFailures} can only tell an agent <em>which</em> id was not found when the
     * refusal carries a message. A bodyless 404 degraded to the bare reason phrase "Not Found". The message
     * matches the Quarkus adapter's {@code NotFoundException} text so both stacks answer an agent alike.
     */
    @Get("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public ExceptionDetailDto detail(@PathVariable String id) {
        ExceptionStore.GroupDetail detail = store.find(id);
        if (detail == null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "exception " + id + " not found");
        }
        return service.detail(detail);
    }

    @Delete
    public HttpResponse<?> clear() {
        store.clear();
        return HttpResponse.noContent();
    }

    @Post("/{id}/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> updateStatus(@PathVariable String id, @Body @Nullable ExceptionStatusUpdateRequest request) {
        try {
            ExceptionGroupDto updated = service.updateStatus(store, id, request == null ? null : request.status());
            return updated == null ? HttpResponse.notFound() : HttpResponse.ok(updated);
        } catch (IllegalArgumentException ex) {
            return HttpResponse.badRequest(
                    Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
        }
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(openStreams, MAX_CONCURRENT_STREAMS, store::subscribe);
    }
}
