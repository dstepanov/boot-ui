package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL-specific: an index the catalog reports as unusable — {@code indisvalid = false} (typically left
 * behind by a failed {@code CREATE INDEX CONCURRENTLY}), {@code indisready = false}, or
 * {@code indislive = false} — still consumes storage and write cost without ever being used by the planner.
 *
 * <p>Three sources of noise are excluded. Partitioned index parents ({@code relkind = 'I'}) are legitimately
 * invalid until every child index is attached, extension-owned indexes are not the user's to fix, and — on
 * PostgreSQL 12+, where {@code pg_stat_progress_create_index} is available — an index that is currently being
 * built {@code CONCURRENTLY} is transiently invalid by design and is excluded until that build either
 * finishes or is abandoned. A unique index reported invalid is called out specifically: until it is fixed, it
 * enforces no uniqueness at all, which is a more serious consequence than simply being unused by the planner.
 * The finding is schema-qualified, and when the catalog cannot be read (a role without access to
 * {@code pg_index}) the rule reports {@code SKIPPED} with that reason rather than a clean result.</p>
 */
final class PostgresInvalidIndexRule extends AbstractDatabaseAdvisorRule {

    PostgresInvalidIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-PG-001",
                "Invalid PostgreSQL indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects PostgreSQL indexes reported unusable by pg_index (indisvalid/indisready/indislive), "
                        + "excluding partitioned index parents, extension-owned indexes, and (PostgreSQL 12+) "
                        + "indexes currently being built CONCURRENTLY, which are transiently invalid by design.",
                "Confirm no CREATE INDEX CONCURRENTLY is currently running against the table, then either "
                        + "rebuild it in place with REINDEX INDEX CONCURRENTLY (PostgreSQL 12+), or drop and "
                        + "recreate it (DROP INDEX CONCURRENTLY followed by CREATE INDEX CONCURRENTLY). An "
                        + "invalid index is never used by the query planner but still pays the full write cost "
                        + "of index maintenance; if it is meant to be unique, it currently enforces no "
                        + "uniqueness at all.",
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
                String uniquenessImpact = index.unique()
                        ? " This index is UNIQUE, so uniqueness is not currently enforced over its columns."
                        : "";
                details.add(schema.dataSourceName() + ": index " + index.index() + " on table " + index.qualifiedTable()
                        + " is unusable (" + index.describeFlags() + ")." + uniquenessImpact);
            }
        }
        return violation(details);
    }
}
