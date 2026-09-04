package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ThreadDumpReport;
import io.github.jdubois.bootui.engine.threads.ThreadDumpService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Threads panel ({@code GET /bootui/api/threads} plus the raw-dump download).
 *
 * <p>{@code GET} returns a filtered, paged snapshot of the JVM's live threads from the shared engine
 * {@link ThreadDumpService} and is passive. The raw text dump is exposed as a {@code POST} so it is treated
 * as an explicit, state-changing action gated by the shared {@code LocalhostGuard} write floor; it runs on
 * the blocking executor since it walks every live stack.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/threads")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ThreadsController {

    private final ThreadDumpService service;

    public ThreadsController(ThreadDumpService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ThreadDumpReport threads(
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String state,
            @QueryValue @Nullable Integer offset,
            @QueryValue @Nullable Integer limit) {
        return service.report(q, state, offset, limit);
    }

    @Post("/download")
    @Produces(MediaType.TEXT_PLAIN)
    public HttpResponse<String> download() {
        String dump = service.rawDump();
        if (dump == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(dump)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"thread-dump.txt\"")
                .contentType(MediaType.TEXT_PLAIN_TYPE);
    }
}
