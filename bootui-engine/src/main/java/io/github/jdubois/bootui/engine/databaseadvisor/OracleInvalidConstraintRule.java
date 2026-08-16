package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle-specific: a primary key, unique, foreign key, or check constraint that is disabled, or enabled
 * without having been validated against the rows that already exist.
 *
 * <p>Oracle constraint state is two independent flags, both worth knowing separately: {@code status}
 * ({@code ENABLED}/{@code DISABLED}) says whether new rows are checked at all, and {@code validated}
 * ({@code VALIDATED}/{@code NOT VALIDATED}) says whether the existing rows were ever confirmed to satisfy it.
 * {@code ALTER TABLE ... ENABLE NOVALIDATE} — the standard way to turn a constraint on for new rows without a
 * full-table validation scan — leaves a constraint that {@code DatabaseMetaData.getImportedKeys()} and every
 * generic JDBC view report as if it were fully enforced.</p>
 *
 * <p>Oracle's own system-generated column-level {@code NOT NULL} check constraint is excluded: it is
 * synthesized automatically for every {@code NOT NULL} column declaration, and reporting one of the dozens a
 * typical schema has would be pure noise rather than something the developer chose to leave incomplete. A
 * user-authored {@code CHECK (...)} constraint — even an unnamed one, which is also system-named — is not
 * excluded, since its search condition is not the exact {@code IS NOT NULL} test Oracle generates.</p>
 */
final class OracleInvalidConstraintRule extends AbstractDatabaseAdvisorRule {

    OracleInvalidConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-002",
                "Disabled or unvalidated Oracle constraints",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects primary key/unique/foreign key/check constraints reported all_constraints.status = "
                        + "DISABLED, or all_constraints.validated = NOT VALIDATED, excluding Oracle's own "
                        + "system-generated column-level NOT NULL check constraints.",
                "Run ALTER TABLE ... ENABLE CONSTRAINT ... (after fixing any offending rows) to enable a "
                        + "disabled constraint, or ALTER TABLE ... VALIDATE CONSTRAINT ... (or ENABLE VALIDATE, "
                        + "which takes a stronger lock) to validate one already enabled without validation. A "
                        + "disabled constraint enforces nothing at all, and one enabled NOVALIDATE may already "
                        + "be violated by existing rows the database never checked.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/ALTER-TABLE.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.ORACLE_CONSTRAINTS, "No Oracle datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_CONSTRAINTS)) {
                continue;
            }
            for (OracleConstraintDetail constraint :
                    schema.vendorFindings().findings(VendorFindingKinds.ORACLE_CONSTRAINTS)) {
                checkConstraint(schema, constraint, details);
            }
        }
        return violation(details);
    }

    private void checkConstraint(SchemaSnapshot schema, OracleConstraintDetail constraint, List<String> details) {
        if (constraint.systemGeneratedNotNull()) {
            return;
        }
        boolean disabled = !constraint.enabled();
        boolean unvalidated = !constraint.validatedAgainstExistingRows();
        if (!disabled && !unvalidated) {
            return;
        }
        String state = disabled && unvalidated
                ? "disabled and not validated against existing rows"
                : disabled ? "disabled" : "enabled but not validated against existing rows";
        details.add(schema.dataSourceName() + ": " + constraint.describeType() + " constraint "
                + constraint.constraintName() + " on " + constraint.qualifiedTable() + " is " + state + ".");
    }
}
