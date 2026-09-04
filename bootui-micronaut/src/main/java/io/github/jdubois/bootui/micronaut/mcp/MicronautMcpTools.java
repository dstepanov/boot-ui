package io.github.jdubois.bootui.micronaut.mcp;

import io.github.jdubois.bootui.core.dto.RestClientTraceRecordingRequest;
import io.github.jdubois.bootui.core.dto.SqlTraceRecordingRequest;
import io.github.jdubois.bootui.engine.mcp.McpArguments;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolDescriptions;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.github.jdubois.bootui.micronaut.web.AiController;
import io.github.jdubois.bootui.micronaut.web.ArchitectureController;
import io.github.jdubois.bootui.micronaut.web.BeansController;
import io.github.jdubois.bootui.micronaut.web.CacheController;
import io.github.jdubois.bootui.micronaut.web.ClaudeCodeController;
import io.github.jdubois.bootui.micronaut.web.ConfigController;
import io.github.jdubois.bootui.micronaut.web.ConnectionPoolsController;
import io.github.jdubois.bootui.micronaut.web.CopilotController;
import io.github.jdubois.bootui.micronaut.web.DatabaseAdvisorController;
import io.github.jdubois.bootui.micronaut.web.EmailController;
import io.github.jdubois.bootui.micronaut.web.ExceptionsController;
import io.github.jdubois.bootui.micronaut.web.FaultToleranceController;
import io.github.jdubois.bootui.micronaut.web.FlywayController;
import io.github.jdubois.bootui.micronaut.web.GitHubController;
import io.github.jdubois.bootui.micronaut.web.HealthController;
import io.github.jdubois.bootui.micronaut.web.HeapDumpController;
import io.github.jdubois.bootui.micronaut.web.HibernateController;
import io.github.jdubois.bootui.micronaut.web.HttpExchangesController;
import io.github.jdubois.bootui.micronaut.web.JvmTuningController;
import io.github.jdubois.bootui.micronaut.web.LiquibaseController;
import io.github.jdubois.bootui.micronaut.web.LiveActivityController;
import io.github.jdubois.bootui.micronaut.web.LiveMemoryController;
import io.github.jdubois.bootui.micronaut.web.LogTailController;
import io.github.jdubois.bootui.micronaut.web.LoggersController;
import io.github.jdubois.bootui.micronaut.web.MappingsController;
import io.github.jdubois.bootui.micronaut.web.MemoryController;
import io.github.jdubois.bootui.micronaut.web.MetricsController;
import io.github.jdubois.bootui.micronaut.web.OverviewController;
import io.github.jdubois.bootui.micronaut.web.PentestingController;
import io.github.jdubois.bootui.micronaut.web.ProfileDiffController;
import io.github.jdubois.bootui.micronaut.web.RestApiController;
import io.github.jdubois.bootui.micronaut.web.RestClientTraceController;
import io.github.jdubois.bootui.micronaut.web.ScheduledController;
import io.github.jdubois.bootui.micronaut.web.SecurityLogsController;
import io.github.jdubois.bootui.micronaut.web.SqlTraceController;
import io.github.jdubois.bootui.micronaut.web.ThreadsController;
import io.github.jdubois.bootui.micronaut.web.TracesController;
import io.github.jdubois.bootui.micronaut.web.VulnerabilitiesController;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the catalog of MCP tools exposed by the BootUI MCP server on Micronaut.
 *
 * <p>The Micronaut twin of the Spring {@code BootUiMcpTools}: each tool is a thin adapter over the same
 * thin JAX-RS resource the browser UI hits, so the agent sees exactly the sanitized DTO shape the UI
 * sees (same {@code SecretMasker}/{@code expose-values} handling, same self-data filtering). Argument
 * normalization (the optional {@code query} filter and the {@code bootui.mcp.max-results} cap on
 * {@code limit}) is applied once by the engine {@code McpDispatcher}, so each handler simply reads
 * {@link McpArguments#query()} / {@link McpArguments#limit()}.
 *
 * <p><strong>Availability gate (B1).</strong> Every tool is gated on
 * {@link MicronautPanelAvailability#isPanelAvailable(String)} — the same source of truth the panel
 * manifest uses — <em>not</em> on whether its backing CDI bean resolves. The engine services are
 * produced unconditionally on Micronaut (they render empty/unavailable when their optional backing is
 * absent), so a resolvability check would wrongly advertise tools (e.g. {@code hibernate_scan} in an
 * app without Hibernate ORM). Gating on panel availability means a tool is advertised iff its backing
 * panel is live, matching the sidebar the user sees.
 *
 * <p>Spring-specific or currently unavailable concepts are deliberately absent: GraalVM readiness,
 * CRaC, condition matches, startup steps, HTTP sessions, Spring Data, Spring Security, DevTools, JMS,
 * and transaction-boundary capture. The {@code get_overview} tool
 * <em>is</em> advertised on Micronaut: the Overview panel is available here (its dashboard renders
 * client-side from the advisor endpoints), and the tool returns the same shell {@code OverviewDto}
 * the Spring adapter exposes.
 */
@io.github.jdubois.bootui.micronaut.RequiresBootUi
@Singleton
public class MicronautMcpTools {

    private final List<McpTool> tools;

    public MicronautMcpTools(
            MicronautPanelAvailability availability,
            ArchitectureController architecture,
            HibernateController hibernate,
            MemoryController memory,
            PentestingController pentesting,
            RestApiController restApi,
            ExceptionsController exceptions,
            LiveActivityController liveActivity,
            SecurityLogsController securityLogs,
            SqlTraceController sqlTrace,
            TracesController traces,
            LogTailController logTail,
            HttpExchangesController httpExchanges,
            HealthController health,
            ConfigController config,
            BeansController beans,
            MappingsController mappings,
            OverviewController overview,
            DatabaseAdvisorController databaseAdvisor,
            VulnerabilitiesController vulnerabilities,
            LoggersController loggers,
            ScheduledController scheduled,
            FaultToleranceController faultTolerance,
            CacheController cache,
            ConnectionPoolsController connectionPools,
            MetricsController metrics,
            LiveMemoryController liveMemory,
            JvmTuningController jvmTuning,
            HeapDumpController heapDump,
            ThreadsController threads,
            ProfileDiffController profileDiff,
            FlywayController flyway,
            LiquibaseController liquibase,
            RestClientTraceController restClientTrace,
            AiController ai,
            EmailController email,
            GitHubController github,
            CopilotController copilot,
            ClaudeCodeController claudeCode) {
        List<McpTool> registry = new ArrayList<>();

        // --- Advisor tools (panel actions; behind the LocalhostGuard write floor) ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "architecture_scan",
                        McpToolDescriptions.micronaut("architecture_scan"),
                        args -> architecture.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_architecture_report",
                        McpToolDescriptions.micronaut("get_architecture_report"),
                        args -> architecture.architecture()));
        addIfAvailable(
                registry,
                availability,
                tool("hibernate_scan", McpToolDescriptions.micronaut("hibernate_scan"), args -> hibernate.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_hibernate_report",
                        McpToolDescriptions.micronaut("get_hibernate_report"),
                        args -> hibernate.hibernate()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "database_advisor_scan",
                        McpToolDescriptions.micronaut("database_advisor_scan"),
                        args -> databaseAdvisor.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_database_advisor_report",
                        McpToolDescriptions.micronaut("get_database_advisor_report"),
                        args -> databaseAdvisor.databaseAdvisor()));
        addIfAvailable(
                registry,
                availability,
                tool("memory_scan", McpToolDescriptions.micronaut("memory_scan"), args -> memory.scan()));
        addIfAvailable(
                registry,
                availability,
                tool("get_memory_report", McpToolDescriptions.micronaut("get_memory_report"), args -> memory.memory()));
        addIfAvailable(
                registry,
                availability,
                tool("pentest_scan", McpToolDescriptions.micronaut("pentest_scan"), args -> pentesting.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_pentest_report",
                        McpToolDescriptions.micronaut("get_pentest_report"),
                        args -> pentesting.pentesting()));
        addIfAvailable(
                registry,
                availability,
                tool("rest_api_scan", McpToolDescriptions.micronaut("rest_api_scan"), args -> restApi.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rest_api_report",
                        McpToolDescriptions.micronaut("get_rest_api_report"),
                        args -> restApi.restApi()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "vulnerabilities_scan",
                        McpToolDescriptions.micronaut("vulnerabilities_scan"),
                        args -> vulnerabilities.scan()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_vulnerabilities_report",
                        McpToolDescriptions.micronaut("get_vulnerabilities_report"),
                        args -> vulnerabilities.dependencies()));

        // --- Diagnostics / runtime tools ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_live_activity",
                        McpToolDescriptions.micronaut("get_live_activity"),
                        args -> liveActivity.activity(args.limit(), null, null, null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool("get_exceptions", McpToolDescriptions.micronaut("get_exceptions"), args -> exceptions.list()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_exception_detail",
                        McpToolDescriptions.micronaut("get_exception_detail"),
                        args -> exceptions.detail(args.id())));
        addIfAvailable(
                registry,
                availability,
                tool("clear_exceptions", McpToolDescriptions.micronaut("clear_exceptions"), args -> {
                    exceptions.clear();
                    return Map.of("cleared", true);
                }));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_security_logs",
                        McpToolDescriptions.micronaut("get_security_logs"),
                        args -> securityLogs.logs(null, null, null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool("get_sql_traces", McpToolDescriptions.micronaut("get_sql_traces"), args -> sqlTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                tool("clear_sql_traces", McpToolDescriptions.micronaut("clear_sql_traces"), args -> sqlTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "pause_sql_trace_recording",
                        McpToolDescriptions.micronaut("pause_sql_trace_recording"),
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "resume_sql_trace_recording",
                        McpToolDescriptions.micronaut("resume_sql_trace_recording"),
                        args -> sqlTrace.recording(new SqlTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                tool("get_traces", McpToolDescriptions.micronaut("get_traces"), args -> traces.list(args.limit())));
        addIfAvailable(
                registry, availability, tool("clear_traces", McpToolDescriptions.micronaut("clear_traces"), args -> {
                    traces.clear();
                    return Map.of("cleared", true);
                }));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_log_tail",
                        McpToolDescriptions.micronaut("get_log_tail"),
                        args -> Map.of("entries", logTail.recent())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_http_exchanges",
                        McpToolDescriptions.micronaut("get_http_exchanges"),
                        args -> httpExchanges.exchanges(null, null, null, null, args.limit())));

        // --- Core context read tools ---
        addIfAvailable(
                registry,
                availability,
                tool("get_overview", McpToolDescriptions.micronaut("get_overview"), args -> overview.overview()));
        addIfAvailable(
                registry,
                availability,
                tool("get_health", McpToolDescriptions.micronaut("get_health"), args -> health.health()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_config",
                        McpToolDescriptions.micronaut("get_config"),
                        args -> config.list(args.query(), null, false, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_beans",
                        McpToolDescriptions.micronaut("get_beans"),
                        args -> beans.beans(args.query(), null, null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_mappings",
                        McpToolDescriptions.micronaut("get_mappings"),
                        args -> mappings.flatMappings(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_loggers",
                        McpToolDescriptions.micronaut("get_loggers"),
                        args -> loggers.loggers(args.query(), null, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_scheduled_tasks",
                        McpToolDescriptions.micronaut("get_scheduled_tasks"),
                        args -> scheduled.scheduled()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_fault_tolerance",
                        McpToolDescriptions.micronaut("get_fault_tolerance"),
                        args -> faultTolerance.faultTolerance()));
        addIfAvailable(
                registry,
                availability,
                tool("get_cache_stats", McpToolDescriptions.micronaut("get_cache_stats"), args -> cache.cache()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_database_connection_pools",
                        McpToolDescriptions.micronaut("get_database_connection_pools"),
                        args -> connectionPools.pools()));

        // --- Additional panel tools ---
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_metrics",
                        McpToolDescriptions.micronaut("get_metrics"),
                        args -> metrics.metrics(
                                args.query(), null, null, null, null, "0", String.valueOf(args.limit()))));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_live_memory",
                        McpToolDescriptions.micronaut("get_live_memory"),
                        args -> liveMemory.memory(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_jvm_tuning",
                        McpToolDescriptions.micronaut("get_jvm_tuning"),
                        args -> jvmTuning.jvmTuning(null, null, null, null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_heap_dump_report",
                        McpToolDescriptions.micronaut("get_heap_dump_report"),
                        args -> heapDump.report("", "")));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "analyze_heap_dump",
                        McpToolDescriptions.micronaut("analyze_heap_dump"),
                        args -> heapDump.analyze()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_threads",
                        McpToolDescriptions.micronaut("get_threads"),
                        args -> threads.threads(args.query(), null, 0, args.limit())));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_profile_diff",
                        McpToolDescriptions.micronaut("get_profile_diff"),
                        args -> profileDiff.profiles()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_flyway_migrations",
                        McpToolDescriptions.micronaut("get_flyway_migrations"),
                        args -> flyway.migrations()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_liquibase_changesets",
                        McpToolDescriptions.micronaut("get_liquibase_changesets"),
                        args -> liquibase.changeSets()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_rest_client_traces",
                        McpToolDescriptions.micronaut("get_rest_client_traces"),
                        args -> restClientTrace.trace()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "clear_rest_client_traces",
                        McpToolDescriptions.micronaut("clear_rest_client_traces"),
                        args -> restClientTrace.clear()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "pause_rest_client_recording",
                        McpToolDescriptions.micronaut("pause_rest_client_recording"),
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(false))));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "resume_rest_client_recording",
                        McpToolDescriptions.micronaut("resume_rest_client_recording"),
                        args -> restClientTrace.recording(new RestClientTraceRecordingRequest(true))));
        addIfAvailable(
                registry,
                availability,
                tool("get_ai_overview", McpToolDescriptions.micronaut("get_ai_overview"), args -> ai.overview()));
        addIfAvailable(
                registry,
                availability,
                tool("get_emails", McpToolDescriptions.micronaut("get_emails"), args -> email.list()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_github_dashboard",
                        McpToolDescriptions.micronaut("get_github_dashboard"),
                        args -> github.dashboard()));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_copilot_sessions",
                        McpToolDescriptions.micronaut("get_copilot_sessions"),
                        args -> copilot.sessions(null, null)));
        addIfAvailable(
                registry,
                availability,
                tool(
                        "get_claude_code_sessions",
                        McpToolDescriptions.micronaut("get_claude_code_sessions"),
                        args -> claudeCode.sessions(null, null)));

        this.tools = List.copyOf(registry);
    }

    /** All tools in advertised order. */
    public List<McpTool> tools() {
        return tools;
    }

    private static void addIfAvailable(List<McpTool> registry, MicronautPanelAvailability availability, McpTool tool) {
        if (availability.isPanelAvailable(tool.panelId())) {
            registry.add(tool);
        }
    }

    /**
     * Builds one advertised tool from the shared {@link McpToolCatalog}.
     *
     * <p>Only the name, description, and handler are adapter-specific. The argument schema, backing panel,
     * and action flag are read back from the catalog, so they cannot be spelled differently here than in the
     * other stacks, and a name this stack is not supposed to advertise fails fast at startup.
     *
     * <p>Every handler is wrapped by {@link MicronautMcpToolFailures} at this single point, so a tool that
     * delegates to a controller method cannot report its client error as a server fault by being registered
     * through a path that forgot to translate.
     */
    private static McpTool tool(String name, String description, Function<McpArguments, Object> handler) {
        McpToolCatalog.Entry entry = McpToolCatalog.require(name, McpToolCatalog.Stack.MICRONAUT);
        return new McpTool(
                name,
                description,
                entry.schema(),
                entry.panelId(),
                entry.action(),
                MicronautMcpToolFailures.translating(handler));
    }
}
