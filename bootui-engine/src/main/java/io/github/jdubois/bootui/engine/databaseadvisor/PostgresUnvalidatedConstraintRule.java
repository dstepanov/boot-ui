package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: a foreign key or check constraint added with {@code NOT VALID} and never validated.
 *
 * <p>{@code ALTER TABLE ... ADD CONSTRAINT ... NOT VALID} is the standard way to add a constraint to a large
 * table without a long lock, with the intent of running {@code VALIDATE CONSTRAINT} afterwards. When that
 * second step never happens the constraint stays half-enforced forever: new rows are checked, existing rows
 * are not, so the data may already violate it and the planner cannot use the constraint for optimization.
 * That is invisible in every generic metadata view — {@code getImportedKeys()} reports the foreign key as if
 * it were fully enforced.</p>
 */
final class PostgresUnvalidatedConstraintRule extends AbstractDatabaseAdvisorRule {

    PostgresUnvalidatedConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-003",
                "PostgreSQL NOT VALID constraint never validated",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects foreign key and check constraints with pg_constraint.convalidated = false, excluding "
                        + "system and extension-owned objects.",
                "Run ALTER TABLE ... VALIDATE CONSTRAINT ... (which takes only a SHARE UPDATE EXCLUSIVE lock) "
                        + "after fixing any offending rows. Until then the constraint is enforced for new rows "
                        + "only: existing rows may already violate it, and the planner cannot rely on it.",
                "https://www.postgresql.org/docs/current/sql-altertable.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS, "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS)) {
                continue;
            }
            for (PostgresUnvalidatedConstraint constraint :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_UNVALIDATED_CONSTRAINTS)) {
                details.add(schema.dataSourceName() + ": " + constraint.describeType() + " constraint "
                        + constraint.constraint() + " on " + constraint.qualifiedTable()
                        + " was added NOT VALID and has never been validated.");
            }
        }
        return violation(details);
    }
}
