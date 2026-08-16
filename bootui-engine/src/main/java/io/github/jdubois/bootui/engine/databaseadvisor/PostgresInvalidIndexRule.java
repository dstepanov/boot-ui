package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: an index the catalog reports as unusable — {@code indisvalid = false} (typically left
 * behind by a failed {@code CREATE INDEX CONCURRENTLY}), {@code indisready = false}, or
 * {@code indislive = false} — still consumes storage and write cost without ever being used by the planner.
 *
 * <p>Two sources of noise are excluded. Partitioned index parents ({@code relkind = 'I'}) are legitimately
 * invalid until every child index is attached, and extension-owned indexes are not the user's to fix. The
 * finding is schema-qualified, and when the catalog cannot be read (a role without access to {@code pg_index})
 * the rule reports {@code SKIPPED} with that reason rather than a clean result.</p>
 */
final class PostgresInvalidIndexRule extends AbstractDatabaseAdvisorRule {

    PostgresInvalidIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-001",
                "Invalid PostgreSQL indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL indexes reported unusable by pg_index (indisvalid/indisready/indislive), "
                        + "excluding partitioned index parents and extension-owned indexes.",
                "Confirm no CREATE INDEX CONCURRENTLY is currently running against the table, then drop and "
                        + "recreate the index (DROP INDEX CONCURRENTLY followed by CREATE INDEX CONCURRENTLY). An "
                        + "invalid index is never used by the query planner but still pays the full write cost of "
                        + "index maintenance.",
                "https://www.postgresql.org/docs/current/sql-createindex.html#SQL-CREATEINDEX-CONCURRENTLY"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.POSTGRESQL);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.POSTGRES_INVALID_INDEXES, "No PostgreSQL datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (!VendorRuleSupport.available(schema, VendorFindingKinds.POSTGRES_INVALID_INDEXES)) {
                continue;
            }
            for (PostgresInvalidIndex index :
                    schema.vendorFindings().findings(VendorFindingKinds.POSTGRES_INVALID_INDEXES)) {
                details.add(schema.dataSourceName() + ": index " + index.index() + " on table " + index.qualifiedTable()
                        + " is unusable (" + index.describeFlags() + ").");
            }
        }
        return violation(details);
    }
}
