package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the HTTP Exchanges panel ({@code GET /bootui/api/http-exchanges}).
 *
 * <p>A thin transport adapter over the shared engine {@link HttpExchangesService}, which masks headers
 * behind the live exposure policy and owns the filtering and paging. The capture itself lives in
 * {@link MicronautHttpExchangeCaptureFilter}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/http-exchanges")
public class HttpExchangesController {

    private final HttpExchangeBuffer buffer;
    private final MicronautExposurePolicy exposure;
    private final SelfTelemetryClassifier selfClassifier;
    private final HttpExchangesService service = new HttpExchangesService();

    public HttpExchangesController(
            HttpExchangeBuffer buffer, MicronautExposurePolicy exposure, SelfTelemetryClassifier selfClassifier) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.selfClassifier = selfClassifier;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HttpExchangesReport exchanges(
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String method,
            @QueryValue @Nullable String statusClass,
            @QueryValue @Nullable Integer offset,
            @QueryValue @Nullable Integer limit) {
        return service.report(
                buffer.snapshot(),
                uri -> !selfClassifier.shouldInclude(selfClassifier.isBootUiPath(uri)),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                q,
                method,
                statusClass,
                offset,
                limit);
    }
}
