package io.github.jdubois.bootui.cli;

import java.util.Map;

/**
 * The one hand-written thing about the command tree: which command path each MCP tool is reached by.
 *
 * <p>Everything else — the arguments, the panel, whether a command mutates, which stacks have it — is read
 * from {@code McpToolCatalog}. Only the naming is a judgement call, because {@code get_spring_data_repositories}
 * has to become {@code bootui repositories} somehow, and no rule derives that.
 *
 * <p>Deliberately test-scoped. It feeds the generator that writes the checked-in manifest, and the tests that
 * pin the mapping total and injective over the catalog, so adding an MCP tool without giving it a command
 * fails the build. The CLI itself reads only the generated manifest.
 *
 * <p>The mapping is the one published in issue #886, with a single deviation: {@code get_ai_overview} is
 * {@code bootui ai overview} rather than {@code bootui overview --ai}. A flag is not a command, and the whole
 * value of this table is that it stays a mechanical one-to-one projection.
 */
final class CliCommandPaths {

    static final Map<String, String> BY_TOOL = Map.ofEntries(
            Map.entry("analyze_heap_dump", "memory heap analyze"),
            Map.entry("architecture_scan", "architecture scan"),
            Map.entry("clear_exceptions", "exceptions clear"),
            Map.entry("clear_rest_client_traces", "rest-client clear"),
            Map.entry("clear_sql_traces", "sql clear"),
            Map.entry("clear_traces", "traces clear"),
            Map.entry("clear_transactions", "tx clear"),
            Map.entry("crac_scan", "crac scan"),
            Map.entry("database_advisor_scan", "db scan"),
            Map.entry("get_ai_overview", "ai overview"),
            Map.entry("get_architecture_report", "architecture report"),
            Map.entry("get_beans", "beans"),
            Map.entry("get_cache_stats", "cache"),
            Map.entry("get_claude_code_sessions", "sessions claude"),
            Map.entry("get_conditions", "conditions"),
            Map.entry("get_config", "config"),
            Map.entry("get_copilot_sessions", "sessions copilot"),
            Map.entry("get_crac_report", "crac report"),
            Map.entry("get_database_advisor_report", "db report"),
            Map.entry("get_database_connection_pools", "db pools"),
            Map.entry("get_dev_services", "dev-services"),
            Map.entry("get_devtools_status", "devtools status"),
            Map.entry("get_emails", "mail"),
            Map.entry("get_exception_detail", "exceptions show"),
            Map.entry("get_exceptions", "exceptions list"),
            Map.entry("get_fault_tolerance", "fault-tolerance"),
            Map.entry("get_flyway_migrations", "db flyway"),
            Map.entry("get_github_dashboard", "github"),
            Map.entry("get_graalvm_report", "graalvm report"),
            Map.entry("get_health", "health"),
            Map.entry("get_heap_dump_report", "memory heap report"),
            Map.entry("get_hibernate_report", "hibernate report"),
            Map.entry("get_http_exchanges", "http exchanges"),
            Map.entry("get_http_sessions", "http sessions"),
            Map.entry("get_jms_activity", "jms"),
            Map.entry("get_jvm_tuning", "jvm tuning"),
            Map.entry("get_kafka_activity", "kafka"),
            Map.entry("get_liquibase_changesets", "db liquibase"),
            Map.entry("get_live_activity", "activity"),
            Map.entry("get_live_memory", "memory live"),
            Map.entry("get_log_tail", "logs tail"),
            Map.entry("get_loggers", "loggers"),
            Map.entry("get_mappings", "mappings"),
            Map.entry("get_memory_report", "memory report"),
            Map.entry("get_metrics", "metrics"),
            Map.entry("get_overview", "overview"),
            Map.entry("get_pentest_report", "pentest report"),
            Map.entry("get_profile_diff", "profile diff"),
            Map.entry("get_rabbitmq_activity", "rabbitmq"),
            Map.entry("get_rest_api_report", "rest-api report"),
            Map.entry("get_rest_client_traces", "rest-client traces"),
            Map.entry("get_scheduled_tasks", "scheduled"),
            Map.entry("get_security_logs", "security logs"),
            Map.entry("get_security_report", "security report"),
            Map.entry("get_spring_data_repositories", "repositories"),
            Map.entry("get_spring_report", "spring report"),
            Map.entry("get_spring_security", "security config"),
            Map.entry("get_sql_traces", "sql traces"),
            Map.entry("get_startup_timeline", "startup"),
            Map.entry("get_threads", "threads"),
            Map.entry("get_traces", "traces list"),
            Map.entry("get_transactions", "tx list"),
            Map.entry("get_vulnerabilities_report", "vulnerabilities report"),
            Map.entry("graalvm_scan", "graalvm scan"),
            Map.entry("hibernate_scan", "hibernate scan"),
            Map.entry("memory_scan", "memory scan"),
            Map.entry("pause_rest_client_recording", "rest-client pause"),
            Map.entry("pause_sql_trace_recording", "sql pause"),
            Map.entry("pause_transaction_recording", "tx pause"),
            Map.entry("pentest_scan", "pentest scan"),
            Map.entry("rest_api_scan", "rest-api scan"),
            Map.entry("resume_rest_client_recording", "rest-client resume"),
            Map.entry("resume_sql_trace_recording", "sql resume"),
            Map.entry("resume_transaction_recording", "tx resume"),
            Map.entry("security_scan", "security scan"),
            Map.entry("spring_scan", "spring scan"),
            Map.entry("trigger_devtools_livereload", "devtools livereload"),
            Map.entry("vulnerabilities_scan", "vulnerabilities scan"));

    private CliCommandPaths() {}
}
