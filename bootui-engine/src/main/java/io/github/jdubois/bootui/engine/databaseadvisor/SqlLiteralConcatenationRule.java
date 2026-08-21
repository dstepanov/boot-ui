package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.sqltrace.SqlStatementNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Statements that look like they embed changing literal values where a bind parameter belongs.
 *
 * <p>The evidence is deliberately narrow, because "this SQL contains a number" is not evidence of anything.
 * A statement is reported only when all of the following hold over the retained SQL Trace window:</p>
 *
 * <ul>
 *   <li>Several executions normalize to the <em>same</em> shape once literals are replaced — so the
 *       application is running the same query repeatedly.</li>
 *   <li>Their raw texts <em>differ</em> — so the values are baked into the statement text and change from
 *       execution to execution, rather than being one fixed constant such as {@code WHERE deleted = 0}.</li>
 *   <li>At least one literal sits in a predicate position ({@code =}, a comparison, {@code IN (…)} or
 *       {@code LIKE}) — so the changing value is filtering data, which is exactly where a bind parameter
 *       belongs and where an unescaped value would be dangerous.</li>
 * </ul>
 *
 * <p>What this rule does <em>not</em> claim: it is not a SQL-injection finding. Concatenating a value that
 * the application itself derived (a computed id, an enum ordinal, a page size) is a performance and
 * plan-cache concern, not a vulnerability, and BootUI cannot see where the value came from. The rule reports
 * a shape worth reviewing and says so; treating it as proof of an exploitable defect would be wrong.</p>
 *
 * <p>Nothing captured here is a value. Evidence lines carry the normalized, literal-free statement shape and
 * counts only; distinct raw texts are counted through hashes that are never retained or displayed, so a
 * concatenated password, token or personal identifier cannot reach the report.</p>
 *
 * <p>The rule {@code SKIPPED}s when no statements were captured. An empty SQL Trace buffer means the panel
 * was never enabled or nothing has run yet — it is not a clean bill of health.</p>
 */
final class SqlLiteralConcatenationRule extends AbstractDatabaseAdvisorRule {

    /** Distinct raw texts of one shape needed before the shape is reported at all. */
    private static final int MIN_VARIANTS = 2;

    /** Distinct raw texts at which the evidence is called strong rather than suggestive. */
    private static final int HIGH_CONFIDENCE_VARIANTS = 3;

    /** Distinct raw texts tracked per shape, bounding memory under a high-cardinality workload. */
    private static final int MAX_TRACKED_VARIANTS = 64;

    /** Shapes examined, bounding the scan when an application runs thousands of distinct statements. */
    private static final int MAX_TRACKED_SHAPES = 500;

    /**
     * How much of a shape is shown. Deliberately well under {@link
     * io.github.jdubois.bootui.engine.support.DetailText#DEFAULT_MAX_CHARS}, so one very long statement
     * cannot push the counts and the confidence qualifier out of the sanitized detail line.
     */
    private static final int MAX_SHAPE_LENGTH = 110;

    SqlLiteralConcatenationRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-RUNTIME-001",
                "Statements that appear to embed literal values instead of bind parameters",
                DatabaseAdvisorCategory.RUNTIME_SQL,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Compares the statements retained by SQL Trace after normalization. Reports a statement shape "
                        + "whose raw text changes between executions while its normalized form stays the same and a "
                        + "changing literal sits in a filtering position, which is the signature of values being "
                        + "concatenated into SQL instead of bound. Evidence is counts and literal-free shapes only; "
                        + "no captured value is retained or shown.",
                "Replace the embedded values with bind parameters (a PreparedStatement placeholder, a JPA query "
                        + "parameter, or the equivalent in your query builder). Bound values let the database reuse "
                        + "one execution plan instead of hard-parsing every variant, keep the statement text stable "
                        + "in monitoring, and remove the class of defect where an untrusted value can change the "
                        + "meaning of the statement. This is a shape worth reviewing, not proof of a vulnerability: "
                        + "BootUI cannot see where the value came from.",
                "https://cheatsheetseries.owasp.org/cheatsheets/Query_Parameterization_Cheat_Sheet.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SqlTraceEntryDto> statements = context.observedStatements();
        if (statements.isEmpty()) {
            return skipped("No statements have been captured by SQL Trace, so runtime SQL could not be "
                    + "inspected. Enable SQL Trace and exercise the application, then run the checks again.");
        }

        Map<String, ShapeEvidence> byShape = new LinkedHashMap<>();
        for (SqlTraceEntryDto statement : statements) {
            if (statement.sql() == null || statement.sql().isBlank()) {
                continue;
            }
            SqlStatementNormalizer.Result normalized = SqlStatementNormalizer.normalize(statement.sql());
            if (normalized.predicateLiteralCount() == 0) {
                continue;
            }
            if (!byShape.containsKey(normalized.fingerprint()) && byShape.size() >= MAX_TRACKED_SHAPES) {
                continue;
            }
            byShape.computeIfAbsent(normalized.fingerprint(), key -> new ShapeEvidence(normalized.sql()))
                    .add(statement);
        }

        List<ShapeEvidence> reportable = byShape.values().stream()
                .filter(ShapeEvidence::isReportable)
                .sorted(Comparator.comparingInt(ShapeEvidence::variantCount)
                        .reversed()
                        .thenComparing(ShapeEvidence::shape))
                .toList();
        if (reportable.isEmpty()) {
            return pass();
        }

        List<String> details = new ArrayList<>();
        for (ShapeEvidence evidence : reportable) {
            details.add(evidence.describe());
        }
        return violation(details);
    }

    /** One normalized statement shape and the counts that decide whether it is reportable. */
    private static final class ShapeEvidence {

        private final String shape;
        private final Set<Integer> rawTextHashes = new LinkedHashSet<>();
        private int executions;
        private int nonPreparedExecutions;

        private ShapeEvidence(String shape) {
            this.shape = shape;
        }

        private void add(SqlTraceEntryDto statement) {
            executions++;
            if (!"PREPARED".equals(statement.statementType())) {
                nonPreparedExecutions++;
            }
            if (rawTextHashes.size() < MAX_TRACKED_VARIANTS) {
                // Only the hash is kept: it proves two executions differed without retaining what differed.
                rawTextHashes.add(statement.sql().hashCode());
            }
        }

        private int variantCount() {
            return rawTextHashes.size();
        }

        private String shape() {
            return shape;
        }

        private boolean isReportable() {
            return rawTextHashes.size() >= MIN_VARIANTS;
        }

        private String describe() {
            String confidence = rawTextHashes.size() >= HIGH_CONFIDENCE_VARIANTS ? "high" : "medium";
            StringBuilder detail = new StringBuilder()
                    .append(truncate(shape))
                    .append(" \u2014 ")
                    .append(executions)
                    .append(executions == 1 ? " execution, " : " executions, ")
                    .append(rawTextHashes.size())
                    .append(rawTextHashes.size() >= MAX_TRACKED_VARIANTS ? "+" : "")
                    .append(rawTextHashes.size() == 1 ? " distinct text" : " distinct texts")
                    .append(", changing literal in a filtering position (confidence: ")
                    .append(confidence);
            if (nonPreparedExecutions > 0) {
                detail.append(", ").append(nonPreparedExecutions).append(" via a plain Statement");
            }
            return detail.append(").").toString();
        }

        private static String truncate(String value) {
            return value.length() <= MAX_SHAPE_LENGTH ? value : value.substring(0, MAX_SHAPE_LENGTH) + "…";
        }
    }
}
