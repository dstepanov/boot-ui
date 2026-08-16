package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreign key columns with no supporting index force full table scans on joins and on the parent side of
 * cascading deletes/updates. This inspects the physical schema only (foreign keys and indexes reported by
 * {@code DatabaseMetaData}, enriched with vendor index semantics), independent of any Hibernate mapping.
 *
 * <p>The check is "is the constraint's <em>complete ordered column list</em> a usable leading prefix of some
 * index?", not "is the first column indexed somewhere?". A composite foreign key {@code (tenant_id, order_id)}
 * is not supported by an index leading on {@code tenant_id} alone, and an index that is invalid, invisible,
 * partial, expression-based or prefix-truncated cannot serve the lookup at all — each of those looks like a
 * perfectly good index in bare JDBC metadata.</p>
 *
 * <p>On Oracle, the leading columns may appear in <em>any</em> order: a composite foreign key's real access
 * pattern (a cascading delete/update check, a join, or a {@code REFERENCES} validation) is a pure multi-column
 * equality lookup, which does not care which of an index's leading key parts binds to which column, and this
 * is Oracle's own documented guidance for what supports a foreign key. Every other dialect keeps the stricter
 * same-order check.</p>
 */
final class MissingForeignKeyIndexRule extends AbstractDatabaseAdvisorRule {

    MissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-002",
                "Foreign key columns without a supporting index",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects foreign keys (from DatabaseMetaData.getImportedKeys()) whose complete ordered column "
                        + "list is not the leading prefix of any usable index on the same table (on Oracle, any "
                        + "order among the leading columns counts). Invalid, invisible, partial, expression-based "
                        + "and prefix-truncated indexes do not count.",
                "Create an index whose leading columns are exactly the foreign key's columns, in the same order. "
                        + "MySQL/InnoDB creates a supporting index automatically for every foreign key column, but "
                        + "PostgreSQL, Oracle and SQL Server do not: joins against the referenced table and "
                        + "cascading deletes/updates on the parent row can force a full table scan on the child "
                        + "table.",
                "https://vladmihalcea.com/default-database-key-indexing/"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead() || !table.metadata().indexesRead()) {
                    continue;
                }
                for (ForeignKeyModel foreignKey : table.foreignKeys()) {
                    checkForeignKey(schema, table, foreignKey, details);
                }
            }
        }
        return violation(details);
    }

    private void checkForeignKey(
            SchemaSnapshot schema, TableModel table, ForeignKeyModel foreignKey, List<String> details) {
        List<String> columns = foreignKey.columns();
        if (columns.isEmpty() || columns.stream().anyMatch(column -> column == null)) {
            return;
        }
        boolean supported = schema.dialect() == Dialect.ORACLE
                ? table.hasUsableLeadingIndexAnyOrder(columns)
                : table.hasUsableLeadingIndex(columns);
        if (supported) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key " + foreignKey.name() + " "
                + columns + " (referencing " + foreignKey.referencedQualifiedName()
                + ") has no usable index leading on those columns.");
    }
}
