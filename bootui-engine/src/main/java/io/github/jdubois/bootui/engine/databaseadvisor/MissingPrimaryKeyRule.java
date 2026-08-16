package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tables with no declared primary key make row-level replication, ORM identity, and safe updates harder.
 *
 * <p>Three classes of table are deliberately excluded, because a missing primary key there is either not the
 * user's decision or not a defect: extension-owned tables (PostgreSQL {@code pg_depend deptype = 'e'}, e.g.
 * PostGIS or {@code pg_stat_statements} bookkeeping), migration bookkeeping tables owned by Flyway/Liquibase,
 * and PostgreSQL child partitions, whose structure comes from the partitioned parent that is analyzed in
 * their place. A table whose {@code getPrimaryKeys()} call failed is skipped too — an unreadable primary key
 * is not an absent one.</p>
 */
final class MissingPrimaryKeyRule extends AbstractDatabaseAdvisorRule {

    /**
     * Bookkeeping tables created by the migration tools themselves. Their shape is owned by Flyway/Liquibase
     * (Liquibase's {@code DATABASECHANGELOG} genuinely has no primary key by design), so telling the user to
     * add one is advice they cannot act on.
     */
    private static final Set<String> MIGRATION_TABLES = Set.of(
            "flyway_schema_history",
            "schema_version",
            "databasechangelog",
            "databasechangeloglock",
            "changelog",
            "changeloglock");

    MissingPrimaryKeyRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-001",
                "Tables without a primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects application tables reported by DatabaseMetaData.getPrimaryKeys() with no primary key "
                        + "columns, excluding system, temporary, extension-owned and migration bookkeeping tables, "
                        + "and PostgreSQL child partitions.",
                "Declare a primary key (a natural key or a surrogate id) on every table. Without one, ORMs cannot "
                        + "establish row identity, logical replication tools cannot target individual rows, and "
                        + "UPDATE/DELETE statements risk affecting more rows than intended.",
                "https://en.wikipedia.org/wiki/Primary_key"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().primaryKeyRead() || isExcluded(table)) {
                    continue;
                }
                if (table.primaryKeyColumns().isEmpty()) {
                    details.add(schema.dataSourceName() + ": table " + table.qualifiedName() + " has no primary key.");
                }
            }
        }
        return violation(details);
    }

    private boolean isExcluded(TableModel table) {
        if (table.extensionOwned()) {
            return true;
        }
        String name = table.name() == null ? "" : table.name().toLowerCase(Locale.ROOT);
        return MIGRATION_TABLES.contains(name);
    }
}
