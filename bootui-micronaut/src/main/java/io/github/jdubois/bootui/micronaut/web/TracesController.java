package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.core.dto.TracesReport;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Traces panel ({@code GET /bootui/api/traces}, its detail view and the clear action).
 *
 * <p>A thin transport adapter over the shared engine {@link TracesService}, which reads the bounded
 * in-memory store BootUI's span processor fills. The panel renders empty — not unavailable — when the
 * application has no OpenTelemetry on its classpath: there is simply nothing to capture.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/traces")
public class TracesController {

    private final TracesService service;

    public TracesController(TracesService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public TracesReport list(@QueryValue(defaultValue = "100") int limit) {
        return service.list(limit);
    }

    @Get("/{traceId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<TraceDetailDto> detail(@PathVariable String traceId) {
        return service.detail(traceId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete
    public HttpResponse<?> clear() {
        service.clear();
        return HttpResponse.noContent();
    }
}
