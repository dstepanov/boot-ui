package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: a sequence that has consumed most of the range it can actually reach, which ends with
 * every insert relying on it failing outright — a well-documented, real-world outage cause.
 *
 * <p>The ceiling is {@code min(pg_sequences.max_value, owning column capacity)}. That distinction is the
 * whole point of this rule: the common failure is a {@code bigint} sequence (max ≈ 9.2 × 10^18, so its own
 * usage percentage stays at 0 forever) feeding an {@code integer} column that dies at 2,147,483,647. A
 * cycling sequence wraps rather than failing and is never reported, the arithmetic is done in arbitrary
 * precision so it cannot overflow, and the owning {@code table.column} is named in the finding so the fix is
 * obvious.</p>
 */
final class PostgresSequenceExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    PostgresSequenceExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-002",
                "PostgreSQL sequence nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL sequences whose last_value has consumed at least " + WARNING_PERCENT_USED
                        + "% of the smaller of the sequence's own maximum and its owning column's capacity. "
                        + "Cycling sequences are excluded.",
                "Widen the owning column (for example ALTER TABLE ... ALTER COLUMN ... TYPE bigint) and the "
                        + "sequence's maximum, or restart the sequence after archiving old rows. A sequence that "
                        + "reaches its effective maximum causes every subsequent insert relying on it to fail.",
                "https://www.postgresql.org/docs/current/view-pg-sequences.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.POSTGRES_SEQUENCES, "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_SEQUENCES)) {
                continue;
            }
            for (PostgresSequenceUsage sequence :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_SEQUENCES)) {
                checkSequence(schema, sequence, details);
            }
        }
        return violation(details);
    }

    private void checkSequence(SchemaSnapshot schema, PostgresSequenceUsage sequence, List<String> details) {
        if (sequence.cycle() || sequence.effectiveMax() == null) {
            return;
        }
        int percentUsed = sequence.percentUsed();
        if (percentUsed < WARNING_PERCENT_USED) {
            return;
        }
        String limitedBy =
                sequence.limitedByColumn() ? " (limited by its owning column type, not the sequence maximum)" : "";
        details.add(schema.dataSourceName() + ": sequence " + sequence.qualifiedName() + " is at " + percentUsed
                + "% of " + sequence.effectiveMax() + limitedBy + " (last_value " + sequence.lastValue()
                + ", owner " + sequence.describeOwner() + ").");
    }
}
