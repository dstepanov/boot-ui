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
 */
final class MissingForeignKeyIndexRule extends AbstractDatabaseAdvisorRule {

    MissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-002",
                "Foreign key columns without a supporting index",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects foreign keys (from DatabaseMetaData.getImportedKeys()) whose complete ordered column "
                        + "list is not the leading prefix of any usable index on the same table. Invalid, "
                        + "invisible, partial, expression-based and prefix-truncated indexes do not count.",
                "Create an index whose leading columns are exactly the foreign key's columns, in the same order. "
                        + "Most databases do not automatically index foreign keys, so joins against the referenced "
                        + "table and cascading deletes/updates on the parent row can force a full table scan on the "
                        + "child table.",
                "https://use-the-index-luke.com/sql/join/foreign-keys"));
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
        if (table.hasUsableLeadingIndex(columns)) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key " + foreignKey.name() + " "
                + columns + " (referencing " + foreignKey.referencedQualifiedName()
                + ") has no usable index leading on those columns.");
    }
}
