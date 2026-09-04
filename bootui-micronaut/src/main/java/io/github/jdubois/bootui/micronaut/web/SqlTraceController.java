package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.SqlTraceInsightsReport;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceReport;
import io.github.jdubois.bootui.engine.sqltrace.RouteTemplateResolver;
import io.github.jdubois.bootui.engine.sqltrace.SqlRequestEvidence;
import io.github.jdubois.bootui.engine.sqltrace.SqlRouteAttribution.Correlation;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceInsightsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.web.CapturedHttpExchange;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.mappings.MicronautMappingProvider;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.sse.Event;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.reactivestreams.Publisher;

/**
 * Controller for the SQL Trace panel ({@code GET /bootui/api/sql-trace}, its insights view, the clear and
 * recording actions, and the SSE stream).
 *
 * <p>A thin transport adapter over the shared engine {@link SqlTraceRecorder} and
 * {@link SqlTraceInsightsService}. Statements are captured by
 * {@code BootUiSqlTraceDataSourceListener}, which wraps the application's datasources; the insights view
 * additionally correlates them with captured HTTP exchanges, so a slow endpoint can be traced to the
 * queries it issued.
 *
 * <p>Captured parameter values are only exposed when parameter capture is on <em>and</em> the live exposure
 * policy allows values — a bound parameter is exactly the kind of data that must not leak by default.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/sql-trace")
public class SqlTraceController {

    private static final String NOT_CONFIGURED = "SQL tracing is not configured";

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private static final Set<Correlation> CORRELATIONS = Set.of(Correlation.TRACE_ID, Correlation.TIME_WINDOW);

    private final SqlTraceRecorder recorder;
    private final HttpExchangeBuffer exchanges;
    private final MicronautMappingProvider mappings;
    private final MicronautExposurePolicy exposure;
    private final AtomicInteger openStreams = new AtomicInteger();

    public SqlTraceController(
            SqlTraceRecorder recorder,
            HttpExchangeBuffer exchanges,
            MicronautMappingProvider mappings,
            MicronautExposurePolicy exposure) {
        this.recorder = recorder;
        this.exchanges = exchanges;
        this.mappings = mappings;
        this.exposure = exposure;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport trace() {
        return report();
    }

    @Get("/insights")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceInsightsReport insights() {
        return new SqlTraceInsightsService(recorder).insights(requestEvidence(), CORRELATIONS, routeTemplates());
    }

    @Post("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport clear() {
        recorder.clear();
        return report();
    }

    @Post("/recording")
    @Produces(MediaType.APPLICATION_JSON)
    public SqlTraceReport recording(@Body @Nullable SqlTraceRecordingRequest request) {
        boolean enabled = (request == null || request.enabled() == null) ? !recorder.isRecording() : request.enabled();
        recorder.setRecording(enabled);
        return report();
    }

    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(openStreams, MAX_CONCURRENT_STREAMS, recorder::subscribe);
    }

    private SqlTraceReport report() {
        if (!recorder.hasWrappedDataSource()) {
            return SqlTraceReport.unavailable(unavailableReason());
        }
        boolean exposeParameters =
                recorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return recorder.report(exposeParameters);
    }

    private String unavailableReason() {
        if (!recorder.isEnabled()) {
            return "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile).";
        }
        return "No DataSource has been wrapped for tracing yet.";
    }

    /**
     * The captured HTTP exchanges the insights view correlates statements against, so a query can be
     * attributed to the request that issued it.
     */
    private List<SqlRequestEvidence> requestEvidence() {
        List<CapturedHttpExchange> captured = exchanges.snapshot().stream()
                .filter(exchange -> exchange.timestamp() != null)
                .toList();
        List<SqlRequestEvidence> evidence = new ArrayList<>(captured.size());
        for (int index = 0; index < captured.size(); index++) {
            evidence.add(toEvidence(captured.get(index), index));
        }
        return List.copyOf(evidence);
    }

    private RouteTemplateResolver routeTemplates() {
        try {
            return mappings.available() ? RouteTemplateResolver.of(mappings.mappings()) : RouteTemplateResolver.empty();
        } catch (RuntimeException ex) {
            return RouteTemplateResolver.empty();
        }
    }

    private static SqlRequestEvidence toEvidence(CapturedHttpExchange exchange, int ordinal) {
        long start = exchange.timestamp().toEpochMilli();
        long end = start + (exchange.durationMs() == null ? 0 : Math.max(0, exchange.durationMs()));
        String path = exchange.uri() == null ? null : exchange.uri().getPath();
        return SqlRequestEvidence.of(ordinal, exchange.method(), path, null, exchange.traceId(), start, end, null);
    }
}
