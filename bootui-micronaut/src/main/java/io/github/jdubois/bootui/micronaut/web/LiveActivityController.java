package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.core.dto.ActivityPageInfo;
import io.github.jdubois.bootui.core.dto.ActivityPersistenceOptionDto;
import io.github.jdubois.bootui.core.dto.ActivitySwitchRequest;
import io.github.jdubois.bootui.core.dto.EmailMessageDto;
import io.github.jdubois.bootui.core.dto.EmailsReport;
import io.github.jdubois.bootui.core.dto.ExceptionDetailDto;
import io.github.jdubois.bootui.core.dto.ExceptionGroupDto;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.LiveActivityReport;
import io.github.jdubois.bootui.core.dto.RequestProfileDto;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.SecurityLogEventDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.core.dto.TraceDetailDto;
import io.github.jdubois.bootui.engine.activity.ActivityCaptureFactory;
import io.github.jdubois.bootui.engine.activity.ActivityCapturePoller;
import io.github.jdubois.bootui.engine.activity.ActivityPage;
import io.github.jdubois.bootui.engine.activity.ActivityPersistenceSettings;
import io.github.jdubois.bootui.engine.activity.ActivityQuery;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchResponse;
import io.github.jdubois.bootui.engine.activity.ActivitySwitchService;
import io.github.jdubois.bootui.engine.activity.SwitchableActivityStore;
import io.github.jdubois.bootui.engine.email.EmailCaptureService;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.exceptions.ExceptionsService;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTaskRunStore;
import io.github.jdubois.bootui.engine.security.SecurityEventBuffer;
import io.github.jdubois.bootui.engine.security.SecurityLogsService;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.telemetry.TracesService;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.engine.web.LiveActivityAssembler;
import io.github.jdubois.bootui.engine.web.RequestProfileAssembler;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.datasource.MicronautDataSourceProvider;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.sse.Event;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.reactivestreams.Publisher;

/**
 * Controller for the Live Activity panel ({@code GET /bootui/api/activity}, the per-request profile, the
 * persistence switch and the SSE stream).
 *
 * <p>This is the panel that merges everything BootUI captures into one timeline, so it reads from every
 * capture point at once: HTTP exchanges, SQL statements, exceptions, security events, scheduled runs,
 * outbound REST client calls, fault-tolerance events and captured email. The merging, correlation and
 * severity model all live in the shared engine {@link LiveActivityAssembler}; this class only gathers the
 * inputs and honours each source's own availability, so a panel the operator disabled contributes nothing.
 *
 * <p>Persistence is opt-in. By default the timeline lives in a bounded in-memory store; when the developer
 * switches it to the application's own datasource, entries are flushed to a table and the panel pages
 * through them instead. The background flush poller that does that is closed with this bean, so a
 * torn-down context cannot leak it.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/activity")
@ExecuteOn(TaskExecutors.BLOCKING)
public class LiveActivityController {

    /** Bound on concurrent live streams, matching the other adapters. */
    static final int MAX_CONCURRENT_STREAMS = 20;

    private final HttpExchangeBuffer buffer;
    private final MicronautExposurePolicy exposure;
    private final SqlTraceRecorder sqlRecorder;
    private final ExceptionStore exceptionStore;
    private final ExceptionsService exceptionsService;
    private final EmailCaptureService emailCaptureService;
    private final SecurityEventBuffer securityBuffer;
    private final ScheduledTaskRunStore scheduledTaskRunStore;
    private final MicronautPanelAvailability panelAvailability;
    private final TracesService tracesService;
    private final SwitchableActivityStore activityStore;
    private final ActivityPersistenceSettings persistenceSettings;
    private final MicronautDataSourceProvider dataSources;
    private final KafkaActivityRecorder kafkaRecorder;
    private final RabbitActivityRecorder rabbitRecorder;
    private final FaultToleranceEventRecorder faultToleranceRecorder;
    private final RestClientTraceRecorder restClientTraceRecorder;
    private final SelfTelemetryClassifier selfClassifier;

    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final LiveActivityAssembler assembler = new LiveActivityAssembler();
    private final RequestProfileAssembler profileAssembler = new RequestProfileAssembler();
    private final SecurityLogsService securityLogs = new SecurityLogsService();
    private final AtomicInteger openStreams = new AtomicInteger();

    private volatile ActivityCapturePoller switchPoller;

    public LiveActivityController(
            HttpExchangeBuffer buffer,
            MicronautExposurePolicy exposure,
            SqlTraceRecorder sqlRecorder,
            ExceptionStore exceptionStore,
            ExceptionsService exceptionsService,
            EmailCaptureService emailCaptureService,
            SecurityEventBuffer securityBuffer,
            ScheduledTaskRunStore scheduledTaskRunStore,
            MicronautPanelAvailability panelAvailability,
            TracesService tracesService,
            SwitchableActivityStore activityStore,
            ActivityPersistenceSettings persistenceSettings,
            MicronautDataSourceProvider dataSources,
            KafkaActivityRecorder kafkaRecorder,
            RabbitActivityRecorder rabbitRecorder,
            FaultToleranceEventRecorder faultToleranceRecorder,
            RestClientTraceRecorder restClientTraceRecorder,
            SelfTelemetryClassifier selfClassifier) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.sqlRecorder = sqlRecorder;
        this.exceptionStore = exceptionStore;
        this.exceptionsService = exceptionsService;
        this.emailCaptureService = emailCaptureService;
        this.securityBuffer = securityBuffer;
        this.scheduledTaskRunStore = scheduledTaskRunStore;
        this.panelAvailability = panelAvailability;
        this.tracesService = tracesService;
        this.activityStore = activityStore;
        this.persistenceSettings = persistenceSettings;
        this.dataSources = dataSources;
        this.kafkaRecorder = kafkaRecorder;
        this.rabbitRecorder = rabbitRecorder;
        this.faultToleranceRecorder = faultToleranceRecorder;
        this.restClientTraceRecorder = restClientTraceRecorder;
        this.selfClassifier = selfClassifier;
    }

    @PreDestroy
    void stopSwitchPoller() {
        ActivityCapturePoller poller = switchPoller;
        if (poller != null) {
            poller.close();
            switchPoller = null;
        }
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public LiveActivityReport activity(
            @QueryValue @Nullable Integer limit,
            @QueryValue @Nullable String type,
            @QueryValue @Nullable String severity,
            @QueryValue @Nullable String q,
            @QueryValue @Nullable Long since,
            @QueryValue @Nullable Long until,
            @QueryValue @Nullable String cursor,
            @QueryValue @Nullable Integer pageSize) {
        LiveActivityReport live = mergedReport(limit == null ? 0 : limit);
        ActivityPersistenceOptionDto persistenceOption = new ActivityPersistenceOptionDto(
                activityStore.persistent(), resolveDataSource() != null, persistenceSettings.tableName());
        if (!activityStore.persistent()) {
            return new LiveActivityReport(
                    live.available(),
                    live.entries(),
                    live.typeCounts(),
                    live.kpis(),
                    live.sources(),
                    live.warnings(),
                    null,
                    persistenceOption);
        }
        ActivityQuery query = new ActivityQuery(
                persistenceSettings.instanceId(),
                type,
                severity,
                q,
                since != null && since > 0 ? since : null,
                until,
                cursor,
                pageSize == null ? 0 : pageSize);
        ActivityPage page = activityStore.query(query);
        return new LiveActivityReport(
                live.available(),
                page.entryDtos(),
                live.typeCounts(),
                live.kpis(),
                live.sources(),
                live.warnings(),
                new ActivityPageInfo(true, page.nextCursor(), page.hasMore()),
                persistenceOption);
    }

    /**
     * Switches the timeline onto the application's own datasource. The engine owns the validation and the
     * refusal messages; this only starts the flush poller when the switch actually succeeded.
     */
    @Post("/use-existing-datasource")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> useExistingDatasource(@Body @Nullable ActivitySwitchRequest request) {
        ActivitySwitchResponse response = new ActivitySwitchService()
                .useExistingDataSource(activityStore, persistenceSettings, resolveDataSource(), request);
        if (response.newSettings() != null) {
            switchPoller = ActivityCaptureFactory.start(
                    activityStore, response.newSettings(), () -> mergedReport(0).entries());
        }
        return HttpResponse.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    /**
     * The merged in-memory timeline. Public because the persistence flush poller reads exactly the same
     * report, so what is stored can never diverge from what the panel shows.
     */
    public LiveActivityReport mergedReport(int limit) {
        HttpExchangesReport requests = requestsReport();
        SqlSnapshot sql = sqlSnapshot();
        boolean securityAvailable = panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS);
        boolean kafkaAvailable = panelAvailability.isPanelAvailable(BootUiPanels.KAFKA)
                && panelAvailability.isPanelEnabled(BootUiPanels.KAFKA)
                && kafkaRecorder.isEnabled();
        boolean rabbitAvailable = panelAvailability.isPanelAvailable(BootUiPanels.RABBITMQ)
                && panelAvailability.isPanelEnabled(BootUiPanels.RABBITMQ)
                && rabbitRecorder.isEnabled();
        EmailsReport emailReport = emailReport();
        boolean emailAvailable = emailReport != null;
        boolean restClientAvailable = restClientActivityAvailable();
        boolean faultToleranceAvailable = panelAvailability.isPanelAvailable(BootUiPanels.FAULT_TOLERANCE)
                && panelAvailability.isPanelEnabled(BootUiPanels.FAULT_TOLERANCE)
                && faultToleranceRecorder.isEnabled();
        LiveActivityReport report = assembler.report(
                requests,
                sql.entries(),
                sql.available(),
                sql.unavailableWarning(),
                exceptionsService.report(exceptionStore).groups(),
                securityEvents(securityAvailable),
                securityAvailable,
                null,
                false,
                scheduledTaskRunStore.runs(),
                null,
                limit,
                kafkaAvailable ? kafkaRecorder.recent() : List.of(),
                kafkaAvailable,
                null,
                false,
                rabbitAvailable ? rabbitRecorder.recent() : List.of(),
                rabbitAvailable,
                emailAvailable ? emailReport.messages() : List.<EmailMessageDto>of(),
                emailAvailable,
                restClientAvailable
                        ? restClientTraceRecorder
                                .report(exposure.maskSecrets(), exposure.valueExposure())
                                .entries()
                        : List.<RestClientTraceEntryDto>of(),
                restClientAvailable,
                faultToleranceAvailable
                        ? faultToleranceRecorder.recent()
                        : List.<FaultToleranceEventRecorder.CapturedEvent>of(),
                faultToleranceAvailable);
        List<ActivityEntryDto> entries = new ArrayList<>(report.entries().size());
        for (ActivityEntryDto entry : report.entries()) {
            boolean profileable = "REQUEST".equals(entry.type())
                    && entry.correlationId() != null
                    && !entry.correlationId().isBlank();
            entries.add(profileable ? withProfileable(entry) : entry);
        }
        return new LiveActivityReport(
                report.available(), entries, report.typeCounts(), report.kpis(), report.sources(), report.warnings());
    }

    /** Everything BootUI captured for one request, assembled into a single waterfall. */
    @Get("/request/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public RequestProfileDto request(@PathVariable String id) {
        HttpExchangesReport requests = requestsReport();
        HttpExchangeDto request = requests.exchanges().stream()
                .filter(exchange -> id.equals(exchange.id()))
                .findFirst()
                .orElse(null);
        String traceId = request == null ? null : request.traceId();
        TraceDetailDto trace = traceId == null || traceId.isBlank()
                ? null
                : tracesService.detail(traceId).orElse(null);
        boolean securityAvailable = panelAvailability.isPanelAvailable(BootUiPanels.SECURITY_LOGS);
        return profileAssembler.profile(
                id,
                request,
                requests.exchanges(),
                sqlSnapshot().entries(),
                allExceptionDetails(),
                securityEvents(securityAvailable),
                trace);
    }

    /**
     * One tick stream over every capture point the timeline merges, so the client re-fetches once when
     * anything changes rather than subscribing to each source separately.
     */
    @Get(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM)
    public Publisher<Event<String>> stream() {
        return SseStreams.updates(
                openStreams,
                MAX_CONCURRENT_STREAMS,
                combined(
                        buffer::subscribe,
                        scheduledTaskRunStore::subscribe,
                        kafkaRecorder::subscribe,
                        rabbitRecorder::subscribe,
                        emailCaptureService::subscribe,
                        restClientChangeSource(),
                        sqlRecorder::subscribe,
                        exceptionStore::subscribe,
                        faultToleranceRecorder::subscribe));
    }

    /** Fans one listener out over several change sources, unsubscribing from all of them together. */
    private static SseStreams.ChangeSource combined(SseStreams.ChangeSource... sources) {
        return onChange -> {
            List<Runnable> unsubscribes = new ArrayList<>(sources.length);
            for (SseStreams.ChangeSource source : sources) {
                unsubscribes.add(source.subscribe(onChange));
            }
            return () -> unsubscribes.forEach(Runnable::run);
        };
    }

    private DataSource resolveDataSource() {
        return dataSources.dataSources().stream()
                .map(io.github.jdubois.bootui.spi.NamedDataSource::dataSource)
                .findFirst()
                .orElse(null);
    }

    private HttpExchangesReport requestsReport() {
        return exchanges.report(
                buffer.snapshot(),
                uri -> !selfClassifier.shouldInclude(selfClassifier.isBootUiPath(uri)),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
    }

    private EmailsReport emailReport() {
        if (!panelAvailability.isPanelAvailable(BootUiPanels.EMAIL)) {
            return null;
        }
        return emailCaptureService.list();
    }

    private SseStreams.ChangeSource restClientChangeSource() {
        return onChange -> restClientTraceRecorder.subscribe(() -> {
            if (restClientActivityAvailable()) {
                onChange.run();
            }
        });
    }

    private boolean restClientActivityAvailable() {
        return panelAvailability.isPanelAvailable(BootUiPanels.REST_CLIENT_TRACE)
                && panelAvailability.isPanelEnabled(BootUiPanels.REST_CLIENT_TRACE)
                && restClientTraceRecorder.isEnabled()
                && restClientTraceRecorder.hasInstrumentedClient();
    }

    private SqlSnapshot sqlSnapshot() {
        boolean available = sqlRecorder.isEnabled() && sqlRecorder.hasWrappedDataSource();
        if (!available) {
            String warning = !sqlRecorder.isEnabled()
                    ? "SQL tracing is disabled (set bootui.sql-trace.enabled=true in a trusted local profile)."
                    : "SQL trace is unavailable until a JDBC datasource is configured.";
            return new SqlSnapshot(List.of(), false, warning);
        }
        boolean exposeParameters =
                sqlRecorder.isCaptureParameters() && exposure.valueExposure() != ValueExposure.METADATA_ONLY;
        return new SqlSnapshot(sqlRecorder.report(exposeParameters).entries(), true, null);
    }

    private List<SecurityLogEventDto> securityEvents(boolean securityAvailable) {
        if (!securityAvailable) {
            return List.of();
        }
        int maxLogs = securityLogs.maxLogs(Integer.MAX_VALUE);
        return securityLogs
                .report(
                        securityBuffer.snapshot(),
                        maxLogs,
                        exposure.maskSecrets(),
                        exposure.valueExposure(),
                        null,
                        null,
                        null,
                        null,
                        null)
                .events();
    }

    private List<ExceptionDetailDto> allExceptionDetails() {
        List<ExceptionDetailDto> details = new ArrayList<>();
        for (ExceptionGroupDto group : exceptionsService.report(exceptionStore).groups()) {
            ExceptionStore.GroupDetail detail = exceptionStore.find(group.id());
            if (detail != null) {
                details.add(exceptionsService.detail(detail));
            }
        }
        return details;
    }

    /** Marks a request entry as having a profile the UI can drill into. */
    private static ActivityEntryDto withProfileable(ActivityEntryDto entry) {
        return new ActivityEntryDto(
                entry.id(),
                entry.type(),
                entry.timestamp(),
                entry.severity(),
                entry.summary(),
                entry.detail(),
                entry.durationMs(),
                entry.correlationId(),
                entry.method(),
                entry.path(),
                entry.status(),
                entry.thread(),
                true,
                entry.parentId(),
                entry.securedPrincipal(),
                entry.sqlNPlusOneSuspected());
    }

    private record SqlSnapshot(List<SqlTraceEntryDto> entries, boolean available, String unavailableWarning) {}
}
