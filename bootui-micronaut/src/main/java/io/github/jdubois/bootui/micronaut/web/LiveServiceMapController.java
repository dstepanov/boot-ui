package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HikariPoolDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.core.dto.HttpExchangeDto;
import io.github.jdubois.bootui.core.dto.HttpExchangesReport;
import io.github.jdubois.bootui.core.dto.RestClientTraceEntryDto;
import io.github.jdubois.bootui.core.dto.ServiceMapReport;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.engine.kafka.KafkaActivityRecorder;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.github.jdubois.bootui.engine.rabbit.RabbitActivityRecorder;
import io.github.jdubois.bootui.engine.restclienttrace.RestClientTraceRecorder;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapAssembler;
import io.github.jdubois.bootui.engine.servicemap.ServiceMapSources;
import io.github.jdubois.bootui.engine.sqltrace.SqlTraceRecorder;
import io.github.jdubois.bootui.engine.telemetry.SelfTelemetryClassifier;
import io.github.jdubois.bootui.engine.web.HttpExchangeBuffer;
import io.github.jdubois.bootui.engine.web.HttpExchangesService;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import java.util.List;

/**
 * Controller for the Live Activity panel's service map ({@code GET /bootui/api/activity/service-map}).
 *
 * <p>Draws what this application actually talked to, from evidence BootUI already captured: inbound
 * requests, outbound client calls, JDBC pools and the statements issued through them. Each source is
 * consulted only when its own panel is both available and enabled, and an unavailable source is reported as
 * <em>absent</em> rather than empty — the difference between "nothing happened" and "we could not see" is
 * what stops the map from implying an application has no database when tracing is simply off.
 *
 * <p>Cache accesses are deliberately not claimed: Micronaut's cache advice is woven at compile time and
 * exposes no access hook, so the map carries no invented cache edges.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/activity")
public class LiveServiceMapController {

    private final HttpExchangeBuffer buffer;
    private final MicronautExposurePolicy exposure;
    private final MicronautPanelAvailability panelAvailability;
    private final SelfTelemetryClassifier selfClassifier;
    private final ConnectionPoolService connectionPools;
    private final SqlTraceRecorder sqlRecorder;
    private final RestClientTraceRecorder restClientTraceRecorder;
    private final KafkaActivityRecorder kafkaRecorder;
    private final RabbitActivityRecorder rabbitRecorder;

    private final HttpExchangesService exchanges = new HttpExchangesService();
    private final ServiceMapAssembler assembler = new ServiceMapAssembler();

    public LiveServiceMapController(
            HttpExchangeBuffer buffer,
            MicronautExposurePolicy exposure,
            MicronautPanelAvailability panelAvailability,
            SelfTelemetryClassifier selfClassifier,
            ConnectionPoolService connectionPools,
            SqlTraceRecorder sqlRecorder,
            RestClientTraceRecorder restClientTraceRecorder,
            KafkaActivityRecorder kafkaRecorder,
            RabbitActivityRecorder rabbitRecorder) {
        this.buffer = buffer;
        this.exposure = exposure;
        this.panelAvailability = panelAvailability;
        this.selfClassifier = selfClassifier;
        this.connectionPools = connectionPools;
        this.sqlRecorder = sqlRecorder;
        this.restClientTraceRecorder = restClientTraceRecorder;
        this.kafkaRecorder = kafkaRecorder;
        this.rabbitRecorder = rabbitRecorder;
    }

    @Get("/service-map")
    @Produces(MediaType.APPLICATION_JSON)
    public ServiceMapReport serviceMap() {
        return assembler.assemble(sources());
    }

    ServiceMapSources sources() {
        List<HttpExchangeDto> inbound = inboundExchanges();
        List<RestClientTraceEntryDto> restCalls = restClientCalls();
        List<HikariPoolDto> pools = jdbcPools();
        SqlTraceRecorder sql = sqlTraceRecorder();
        // Parameter bindings are never requested: the map only carries the coarse statement category.
        List<SqlTraceEntryDto> statements =
                sql == null ? List.of() : sql.report(false).entries();
        List<String> tracedDataSources = sql == null ? List.of() : sql.dataSourceNames();
        boolean kafkaAvailable = usable(BootUiPanels.KAFKA) && kafkaRecorder.isEnabled();
        boolean rabbitAvailable = usable(BootUiPanels.RABBITMQ) && rabbitRecorder.isEnabled();
        return new ServiceMapSources(
                inbound != null,
                inbound == null ? List.of() : inbound,
                restCalls != null,
                restCalls == null ? List.of() : restCalls,
                pools != null,
                pools == null ? List.of() : pools,
                sql != null,
                statements,
                tracedDataSources,
                kafkaAvailable,
                kafkaAvailable ? kafkaRecorder.recent() : List.of(),
                rabbitAvailable,
                rabbitAvailable ? rabbitRecorder.recent() : List.of(),
                false,
                List.of());
    }

    private List<HttpExchangeDto> inboundExchanges() {
        if (!usable(BootUiPanels.HTTP_EXCHANGES)) {
            return null;
        }
        HttpExchangesReport report = exchanges.report(
                buffer.snapshot(),
                uri -> !selfClassifier.shouldInclude(selfClassifier.isBootUiPath(uri)),
                exposure.maskSecrets(),
                exposure.valueExposure(),
                null,
                null,
                null,
                null,
                null);
        return report.unavailableReason() != null ? null : report.exchanges();
    }

    private List<RestClientTraceEntryDto> restClientCalls() {
        if (!usable(BootUiPanels.REST_CLIENT_TRACE)
                || !restClientTraceRecorder.isEnabled()
                || !restClientTraceRecorder.hasInstrumentedClient()) {
            return null;
        }
        return restClientTraceRecorder
                .report(exposure.maskSecrets(), exposure.valueExposure())
                .entries();
    }

    private List<HikariPoolDto> jdbcPools() {
        if (!usable(BootUiPanels.DATABASE_CONNECTION_POOLS)) {
            return null;
        }
        HikariPoolsReport report = connectionPools.report();
        return report.hikariPresent() ? report.pools() : null;
    }

    private SqlTraceRecorder sqlTraceRecorder() {
        if (!usable(BootUiPanels.SQL_TRACE)) {
            return null;
        }
        return sqlRecorder.isEnabled() && sqlRecorder.hasWrappedDataSource() ? sqlRecorder : null;
    }

    private boolean usable(String panelId) {
        return panelAvailability.isPanelAvailable(panelId) && panelAvailability.isPanelEnabled(panelId);
    }
}
