package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.github.jdubois.bootui.engine.heapdump.HeapDumpService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.nio.file.Path;
import java.util.Map;

/**
 * Controller for the Heap Dump panel ({@code GET /bootui/api/heap-dump} plus its capture, analyze, delete
 * and download actions).
 *
 * <p>A thin transport adapter over the shared engine {@link HeapDumpService}, which owns the dump
 * directory, the retention cap and the histogram analysis. Every action runs on the blocking executor —
 * capturing or analyzing a heap dump is slow, file-bound work that must never occupy an event loop.
 *
 * <p>The raw download stays off by default and 404s unless {@code bootui.heap-dump.allow-raw-download} is
 * enabled: a heap dump contains live application data, so handing the file over is an explicit opt-in.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/heap-dump")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HeapDumpController {

    private final HeapDumpService service;

    public HeapDumpController(HeapDumpService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport report(
            @QueryValue(defaultValue = "") String filter, @QueryValue(defaultValue = "") String smartFilter) {
        return service.report(filter, smartFilter);
    }

    @Post("/capture")
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport capture(@QueryValue(defaultValue = "true") boolean live) {
        return service.capture(live);
    }

    @Post("/analyze")
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport analyze() {
        return service.analyze();
    }

    @Post("/delete")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public HeapDumpReport delete(@Body @Nullable Map<String, String> form) {
        return service.delete(form == null ? null : form.get("name"));
    }

    @Get("/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public HttpResponse<?> download(@QueryValue @Nullable String name) {
        if (!service.rawDownloadAllowed()) {
            return HttpResponse.notFound();
        }
        Path file = service.resolveExisting(name);
        if (file == null) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(new SystemFile(file.toFile(), MediaType.APPLICATION_OCTET_STREAM_TYPE))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.getFileName() + "\"");
    }
}
