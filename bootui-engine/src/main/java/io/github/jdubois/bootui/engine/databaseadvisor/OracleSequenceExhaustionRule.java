package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Oracle-specific: a non-cycling sequence — including the internal sequence backing a
 * {@code GENERATED ... AS IDENTITY} column — that has consumed most of the range it can actually reach.
 * When it arrives, every insert relying on the sequence fails outright, the same class of outage
 * {@code DB-PG-002} and {@code DB-MYSQL-003} catch for PostgreSQL and MySQL/MariaDB.
 *
 * <p>Unlike PostgreSQL, there is no separate owning-column capacity to cross-reference: Oracle's single
 * generic {@code NUMBER} identifier type means the sequence's own {@code max_value} is always the real
 * ceiling. {@code all_sequences.last_number} already reflects the cache's reserved high-water mark, not
 * merely committed consumption, which makes this measurement conservative (it can flag a sequence slightly
 * before it is truly that close, never after).</p>
 *
 * <p>Session, scalable, and sharded sequences are excluded: each has range or reset semantics ({@code
 * SESSION_FLAG}, {@code SCALE_FLAG}, {@code SHARDED_FLAG}) a plain percent-of-{@code max_value} reading would
 * misrepresent, so this only reports the ordinary case that reading is actually valid for.</p>
 */
final class OracleSequenceExhaustionRule extends AbstractDatabaseAdvisorRule {

    static final int WARNING_PERCENT_USED = 80;

    OracleSequenceExhaustionRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-003",
                "Oracle sequence or identity generator nearing exhaustion",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects non-cycling Oracle sequences (all_sequences.cycle_flag = 'N') — including one "
                        + "backing a GENERATED ... AS IDENTITY column — whose last_number has consumed at "
                        + "least " + WARNING_PERCENT_USED + "% of the range between min_value and max_value. "
                        + "Session, scalable and sharded sequences are excluded.",
                "Widen the sequence's MAXVALUE (ALTER SEQUENCE ... MAXVALUE ...), or the owning IDENTITY "
                        + "column's precision if the sequence backs one, or restart the sequence after archiving "
                        + "old rows. A non-cycling sequence that reaches its maximum causes every subsequent "
                        + "insert relying on it to fail.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/ALTER-SEQUENCE.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.ORACLE_SEQUENCES, "No Oracle datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_SEQUENCES)) {
                continue;
            }
            Map<String, OracleIdentityColumn> identityBySequence = identityColumnsBySequence(schema);
            for (OracleSequenceUsage sequence : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_SEQUENCES)) {
                checkSequence(schema, sequence, identityBySequence, details);
            }
        }
        return violation(details);
    }

    private Map<String, OracleIdentityColumn> identityColumnsBySequence(SchemaSnapshot schema) {
        Map<String, OracleIdentityColumn> bySequence = new HashMap<>();
        if (!VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_IDENTITY_COLUMNS)) {
            return bySequence;
        }
        for (OracleIdentityColumn identityColumn :
                schema.vendorFindings().findings(VendorFindingKinds.ORACLE_IDENTITY_COLUMNS)) {
            if (identityColumn.sequenceName() != null) {
                bySequence.put(identityColumn.sequenceName(), identityColumn);
            }
        }
        return bySequence;
    }

    private void checkSequence(
            SchemaSnapshot schema,
            OracleSequenceUsage sequence,
            Map<String, OracleIdentityColumn> identityBySequence,
            List<String> details) {
        if (sequence.cycle() || sequence.excluded()) {
            return;
        }
        int percentUsed = sequence.percentUsed();
        if (percentUsed < WARNING_PERCENT_USED) {
            return;
        }
        OracleIdentityColumn identityColumn = identityBySequence.get(sequence.sequence());
        String owner = identityColumn == null ? "" : " backing IDENTITY column " + identityColumn.qualifiedColumn();
        details.add(schema.dataSourceName() + ": sequence " + sequence.qualifiedName() + owner + " is at "
                + percentUsed + "% of its range (last_number " + sequence.lastNumber() + " of max_value "
                + sequence.maxValue() + ").");
    }
}
