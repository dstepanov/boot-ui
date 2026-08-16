package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDataSourceDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorDiagnosticDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorScanStatusDto;
import io.github.jdubois.bootui.core.dto.DatabaseAdvisorSeverityCountDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.hibernate.EntityDiscovery;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.support.SeverityOrder;
import io.github.jdubois.bootui.spi.NamedDataSource;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Bounded, on-demand Database Advisor: introspects the host application's physical schema through plain JDBC
 * {@code DatabaseMetaData} (with PostgreSQL/MySQL/MariaDB catalog augmentation) and, when a Hibernate
 * metamodel is also available for the same application, cross-references it against the mapped entities. It
 * never executes DDL, never queries application data, and never intercepts runtime queries.
 *
 * <p>The scan is honest about what it could not do. A datasource that refused a connection, a table whose
 * metadata could not be read, a catalog view a role cannot see, a bound that truncated the analysis, and a
 * rule that was skipped or errored all reach the report as diagnostics and as an explicit per-datasource
 * status — never as a passing check. That is why {@code DISABLED} now means only "there is no datasource to
 * inspect": a scan where every datasource failed reports {@code ERROR}.</p>
 */
public final class DatabaseAdvisorScanner {

    private static final String ANALYZER = "BootUI Database Advisor";
    private static final String DISCLAIMER =
            "Read-only JDBC schema introspection (tables, columns, primary/foreign keys, indexes) via "
                    + "DatabaseMetaData, with PostgreSQL and MySQL/MariaDB catalog augmentation, plus "
                    + "cross-reference checks against the Hibernate metamodel when both are available. These checks "
                    + "are review prompts, not verdicts.";
    private static final Comparator<DatabaseAdvisorRuleResultDto> IMPORTANCE_ORDER = Comparator.comparingInt(
                    (DatabaseAdvisorRuleResultDto result) -> SeverityOrder.rank(result.severity()))
            .thenComparing(Comparator.comparingInt(DatabaseAdvisorRuleResultDto::violationCount)
                    .reversed())
            .thenComparing(DatabaseAdvisorRuleResultDto::id);

    private final Supplier<List<NamedDataSource>> dataSourceSupplier;
    private final Supplier<EntityDiscovery> entityDiscoverySupplier;
    private final Clock clock;
    private final DatabaseAdvisorLimits limits;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    public static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock) {
        return new DatabaseAdvisorScanner(
                dataSourceSupplier, entityDiscoverySupplier, clock, DatabaseAdvisorLimits.DEFAULTS);
    }

    private DatabaseAdvisorScanner(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        this.dataSourceSupplier = dataSourceSupplier;
        this.entityDiscoverySupplier = entityDiscoverySupplier;
        this.clock = clock;
        this.limits = limits;
    }

    /** Test seam: the same scanner running under explicit bounds. */
    static DatabaseAdvisorScanner using(
            Supplier<List<NamedDataSource>> dataSourceSupplier,
            Supplier<EntityDiscovery> entityDiscoverySupplier,
            Clock clock,
            DatabaseAdvisorLimits limits) {
        return new DatabaseAdvisorScanner(dataSourceSupplier, entityDiscoverySupplier, clock, limits);
    }

    public DatabaseAdvisorReport initialReport() {
        return new ReportBuilder()
                .status("NOT_SCANNED")
                .message("Database Advisor has not run yet. Click Run Database checks to inspect the physical schema.")
                .build();
    }

    public DatabaseAdvisorReport scan() {
        return singleFlight.run(ActionOperations.DATABASE_ADVISOR_SCAN, this::doScan);
    }

    private DatabaseAdvisorReport doScan() {
        List<NamedDataSource> dataSources = safeDataSources();
        if (dataSources.isEmpty()) {
            return new ReportBuilder()
                    .status("DISABLED")
                    .message("No DataSource beans were found to inspect.")
                    .scannedAt(clock.millis())
                    .build();
        }

        ScanBudget budget = ScanBudget.of(limits.scanBudget());
        List<SchemaSnapshot> schemas = dataSources.stream()
                .map(dataSource ->
                        SchemaIntrospector.introspect(dataSource.name(), dataSource.dataSource(), budget, limits))
                .toList();
        List<SchemaSnapshot> available =
                schemas.stream().filter(SchemaSnapshot::available).toList();

        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>(schemaDiagnostics(schemas));
        List<DatabaseAdvisorDataSourceDto> dataSourceStatuses = dataSourceStatuses(schemas);
        boolean truncated = schemas.stream().anyMatch(SchemaSnapshot::truncated);

        if (available.isEmpty()) {
            return new ReportBuilder()
                    .status("ERROR")
                    .message("The physical schema could not be read for any of the " + schemas.size()
                            + " discovered datasources.")
                    .scannedAt(clock.millis())
                    .dataSourceNames(dataSourceNames(dataSources))
                    .dataSources(dataSourceStatuses)
                    .diagnostics(diagnostics)
                    .build();
        }

        EntityDiscovery entityDiscovery = safeEntityDiscovery();
        boolean hibernateAvailable = !entityDiscovery.entities().isEmpty();
        List<MappedEntityFacts> mappedEntities = HibernateSchemaBridge.toMappedEntities(entityDiscovery.entities());

        DatabaseAdvisorContext context = new DatabaseAdvisorContext(schemas, hibernateAvailable, mappedEntities);
        List<DatabaseAdvisorRuleResultDto> results = DatabaseAdvisorRuleRegistry.activeRules().stream()
                .map(rule -> rule.evaluate(context))
                .toList();
        diagnostics.addAll(ruleDiagnostics(results));

        int tablesAnalyzed = context.tableCount();
        int rulesSkipped = countStatus(results, DatabaseAdvisorRuleSupport.SKIPPED);
        int rulesErrored = countStatus(results, DatabaseAdvisorRuleSupport.ERROR);
        boolean allAvailable = available.size() == schemas.size();
        boolean allComplete = available.stream().allMatch(SchemaSnapshot::complete);
        boolean complete = allAvailable && allComplete && !truncated && rulesErrored == 0;

        StringBuilder message = new StringBuilder("Database Advisor completed against ")
                .append(tablesAnalyzed)
                .append(tablesAnalyzed == 1 ? " table" : " tables")
                .append(" across ")
                .append(available.size())
                .append(available.size() == 1 ? " datasource" : " datasources")
                .append('.');
        if (!allAvailable) {
            message.append(" ").append(schemas.size() - available.size()).append(" datasource(s) could not be read.");
        }
        if (truncated) {
            message.append(" A scan bound was reached, so some findings may be missing.");
        } else if (!allComplete) {
            message.append(" Some schema or catalog metadata could not be read; see the diagnostics.");
        }
        if (rulesErrored > 0) {
            message.append(" ").append(rulesErrored).append(" rule(s) failed to evaluate.");
        }

        return new ReportBuilder()
                .status(complete ? "SCANNED" : "PARTIAL")
                .message(message.toString())
                .scannedAt(clock.millis())
                .dataSourceNames(dataSourceNames(dataSources))
                .dataSources(dataSourceStatuses)
                .tablesAnalyzed(tablesAnalyzed)
                .results(results)
                .rulesSkipped(rulesSkipped)
                .rulesErrored(rulesErrored)
                .truncated(truncated)
                .diagnostics(diagnostics)
                .build();
    }

    public DatabaseAdvisorReport applyDismissals(DatabaseAdvisorReport report, Set<String> dismissedIds) {
        if (report == null || dismissedIds == null || dismissedIds.isEmpty()) {
            return report;
        }
        List<DatabaseAdvisorRuleResultDto> marked = report.results().stream()
                .map(result -> result.withDismissed(dismissedIds.contains(result.id())))
                .toList();
        List<DatabaseAdvisorRuleResultDto> active =
                marked.stream().filter(result -> !result.dismissed()).toList();
        int violationsFound = active.size();
        DatabaseAdvisorScanStatusDto scan = report.scan();
        DatabaseAdvisorScanStatusDto updatedScan = new DatabaseAdvisorScanStatusDto(
                scan.analyzer(),
                scan.status(),
                scan.message(),
                scan.scannedAt(),
                scan.rulesEvaluated(),
                scan.tablesAnalyzed(),
                violationsFound);
        return new DatabaseAdvisorReport(
                report.localOnly(),
                report.disclaimer(),
                report.dataSourceNames(),
                report.dataSources(),
                report.tablesAnalyzed(),
                report.rulesEvaluated(),
                violationsFound,
                report.rulesSkipped(),
                report.rulesErrored(),
                report.truncated(),
                severityCounts(active),
                updatedScan,
                marked,
                report.diagnostics());
    }

    private List<DatabaseAdvisorDiagnosticDto> schemaDiagnostics(List<SchemaSnapshot> schemas) {
        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            for (SchemaDiagnostic diagnostic : schema.diagnostics()) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(
                        diagnostic.source(), diagnostic.level(), diagnostic.message()));
            }
        }
        return diagnostics;
    }

    /**
     * Rules that could not run are reported too: a skipped vendor rule is normal and informational, while a
     * rule that threw is a warning — neither may silently look like a passing check.
     */
    private List<DatabaseAdvisorDiagnosticDto> ruleDiagnostics(List<DatabaseAdvisorRuleResultDto> results) {
        List<DatabaseAdvisorDiagnosticDto> diagnostics = new ArrayList<>();
        for (DatabaseAdvisorRuleResultDto result : results) {
            String reason = result.sampleViolations().isEmpty()
                    ? "No reason was reported."
                    : result.sampleViolations().get(0);
            if (DatabaseAdvisorRuleSupport.SKIPPED.equals(result.status())) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(result.id(), SchemaDiagnostic.INFO, reason));
            } else if (DatabaseAdvisorRuleSupport.ERROR.equals(result.status())) {
                diagnostics.add(new DatabaseAdvisorDiagnosticDto(result.id(), SchemaDiagnostic.WARNING, reason));
            }
        }
        return diagnostics;
    }

    private List<DatabaseAdvisorDataSourceDto> dataSourceStatuses(List<SchemaSnapshot> schemas) {
        List<DatabaseAdvisorDataSourceDto> statuses = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            String status;
            String message = null;
            if (!schema.available()) {
                status = "FAILED";
                message = schema.error();
            } else if (!schema.complete()) {
                status = "PARTIAL";
                message = firstProblem(schema);
            } else {
                status = "AVAILABLE";
            }
            statuses.add(new DatabaseAdvisorDataSourceDto(
                    schema.dataSourceName(),
                    schema.available() ? schema.describeProduct() : null,
                    schema.dialect().label(),
                    schema.identifierCase(),
                    status,
                    message,
                    schema.tables().size(),
                    schema.truncated()));
        }
        return statuses;
    }

    private String firstProblem(SchemaSnapshot schema) {
        return schema.diagnostics().stream()
                .filter(diagnostic -> !SchemaDiagnostic.INFO.equals(diagnostic.level()))
                .map(SchemaDiagnostic::message)
                .findFirst()
                .orElse(null);
    }

    private List<NamedDataSource> safeDataSources() {
        try {
            List<NamedDataSource> dataSources = dataSourceSupplier.get();
            return dataSources == null ? List.of() : dataSources;
        } catch (RuntimeException | LinkageError ex) {
            return List.of();
        }
    }

    private EntityDiscovery safeEntityDiscovery() {
        try {
            EntityDiscovery discovery = entityDiscoverySupplier.get();
            return discovery == null
                    ? EntityDiscovery.empty("No EntityManagerFactory beans are available.")
                    : discovery;
        } catch (RuntimeException | LinkageError ex) {
            return EntityDiscovery.empty(ex.getMessage());
        }
    }

    private static List<DatabaseAdvisorSeverityCountDto> severityCounts(List<DatabaseAdvisorRuleResultDto> results) {
        Map<String, Integer> counts = SeverityOrder.counts(
                results, DatabaseAdvisorScanner::isViolation, DatabaseAdvisorRuleResultDto::severity);
        return counts.entrySet().stream()
                .map(entry -> new DatabaseAdvisorSeverityCountDto(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static List<DatabaseAdvisorRuleResultDto> violationResults(List<DatabaseAdvisorRuleResultDto> results) {
        return results.stream()
                .filter(DatabaseAdvisorScanner::isViolation)
                .sorted(IMPORTANCE_ORDER)
                .toList();
    }

    private static int countStatus(List<DatabaseAdvisorRuleResultDto> results, String status) {
        return (int) results.stream()
                .filter(result -> status.equals(result.status()))
                .count();
    }

    private static List<String> dataSourceNames(List<NamedDataSource> dataSources) {
        return dataSources.stream()
                .map(NamedDataSource::name)
                .distinct()
                .sorted()
                .toList();
    }

    private static boolean isViolation(DatabaseAdvisorRuleResultDto result) {
        return DatabaseAdvisorRuleSupport.VIOLATION.equals(result.status());
    }

    /** Assembles the wire report so every exit path reports the same shape. */
    private static final class ReportBuilder {

        private String status = "NOT_SCANNED";
        private String message;
        private Long scannedAt;
        private List<String> dataSourceNames = List.of();
        private List<DatabaseAdvisorDataSourceDto> dataSources = List.of();
        private int tablesAnalyzed;
        private List<DatabaseAdvisorRuleResultDto> results = List.of();
        private int rulesSkipped;
        private int rulesErrored;
        private boolean truncated;
        private List<DatabaseAdvisorDiagnosticDto> diagnostics = List.of();

        ReportBuilder status(String value) {
            this.status = value;
            return this;
        }

        ReportBuilder message(String value) {
            this.message = value;
            return this;
        }

        ReportBuilder scannedAt(Long value) {
            this.scannedAt = value;
            return this;
        }

        ReportBuilder dataSourceNames(List<String> value) {
            this.dataSourceNames = value;
            return this;
        }

        ReportBuilder dataSources(List<DatabaseAdvisorDataSourceDto> value) {
            this.dataSources = value;
            return this;
        }

        ReportBuilder tablesAnalyzed(int value) {
            this.tablesAnalyzed = value;
            return this;
        }

        ReportBuilder results(List<DatabaseAdvisorRuleResultDto> value) {
            this.results = value;
            return this;
        }

        ReportBuilder rulesSkipped(int value) {
            this.rulesSkipped = value;
            return this;
        }

        ReportBuilder rulesErrored(int value) {
            this.rulesErrored = value;
            return this;
        }

        ReportBuilder truncated(boolean value) {
            this.truncated = value;
            return this;
        }

        ReportBuilder diagnostics(List<DatabaseAdvisorDiagnosticDto> value) {
            this.diagnostics = value;
            return this;
        }

        DatabaseAdvisorReport build() {
            List<DatabaseAdvisorRuleResultDto> violations = violationResults(results);
            DatabaseAdvisorScanStatusDto scan = new DatabaseAdvisorScanStatusDto(
                    ANALYZER, status, message, scannedAt, results.size(), tablesAnalyzed, violations.size());
            return new DatabaseAdvisorReport(
                    true,
                    DISCLAIMER,
                    List.copyOf(dataSourceNames),
                    List.copyOf(dataSources),
                    tablesAnalyzed,
                    results.size(),
                    violations.size(),
                    rulesSkipped,
                    rulesErrored,
                    truncated,
                    severityCounts(violations),
                    scan,
                    violations,
                    List.copyOf(diagnostics));
        }
    }
}
