package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.engine.metrics.MetricsReportProvider;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import java.util.List;
import java.util.Map;

/**
 * Controller for the Metrics panel ({@code GET /bootui/api/metrics} and its {@code /detail} drill-down).
 *
 * <p>A thin transport adapter over the shared engine {@link MetricsReportProvider}, which reads the
 * application's Micrometer registry when it has one and otherwise renders the panel as unavailable.
 * Paging/filter arguments are passed through as raw strings so the engine owns their validation and
 * reports an invalid value as a 400 with its own message, identically on every adapter.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/metrics")
public class MetricsController {

    private final MetricsReportProvider provider;

    public MetricsController(MetricsReportProvider provider) {
        this.provider = provider;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> metrics(
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String type,
            @QueryValue @Nullable String group,
            @QueryValue @Nullable String provenance,
            @QueryValue @Nullable String explanation,
            @QueryValue @Nullable String offset,
            @QueryValue @Nullable String limit) {
        try {
            MetricsReport report = provider.metrics(q, type, group, provenance, explanation, offset, limit);
            return HttpResponse.ok(report);
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    @Get("/detail")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> metric(
            @QueryValue @Nullable String name,
            @QueryValue("tag") @Nullable List<String> tagFilters,
            @QueryValue @Nullable String offset,
            @QueryValue @Nullable String limit) {
        try {
            return HttpResponse.ok(provider.metric(name, tagFilters == null ? List.of() : tagFilters, offset, limit));
        } catch (IllegalArgumentException ex) {
            return badRequest(ex);
        }
    }

    private HttpResponse<?> badRequest(IllegalArgumentException ex) {
        return HttpResponse.badRequest(Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
    }
}
