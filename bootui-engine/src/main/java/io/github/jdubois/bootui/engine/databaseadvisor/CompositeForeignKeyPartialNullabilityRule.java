package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * A composite (multi-column) foreign key where some, but not all, of its child columns are nullable.
 *
 * <p>The SQL standard's default matching rule for a composite foreign key ({@code MATCH SIMPLE}, what every
 * mainstream database uses unless {@code MATCH FULL} is explicitly requested) skips the referential check
 * entirely whenever <em>any</em> one of the constraint's columns is {@code NULL} — even the columns that do
 * have a value. A row like {@code (tenant_id = 5, order_id = NULL)} is therefore accepted unconditionally: the
 * database never confirms that {@code tenant_id = 5} actually exists in the referenced table, which usually
 * is not what a schema with a {@code NOT NULL} sibling column intended. A composite key that is fully
 * {@code NOT NULL} (fully enforced) or fully nullable (a deliberately optional relationship) is not flagged —
 * only the mixed case, where the inconsistency is itself the signal that something was probably missed.</p>
 *
 * <p>Only columns whose nullability was read with certainty count either way: a column the driver reports as
 * {@code UNKNOWN} nullable is not counted as either nullable or {@code NOT NULL}, so this never fires on
 * incomplete metadata.</p>
 */
final class CompositeForeignKeyPartialNullabilityRule extends AbstractDatabaseAdvisorRule {

    CompositeForeignKeyPartialNullabilityRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-008",
                "Composite foreign key with partially nullable columns",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects a multi-column foreign key where at least one column is definitely nullable and at "
                        + "least one other is definitely NOT NULL. Columns with unknown nullability are not "
                        + "counted either way.",
                "Either make every column in the composite foreign key NOT NULL (if the relationship is "
                        + "required) or make them all nullable (if it is genuinely optional). With the default "
                        + "MATCH SIMPLE rule, a row with any one column NULL bypasses the referential check for "
                        + "the whole constraint, including the columns that do have a value.",
                "https://www.postgresql.org/docs/current/ddl-constraints.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead() || !table.metadata().columnsRead()) {
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
        if (columns.size() < 2) {
            return;
        }
        int nullableCount = 0;
        int notNullCount = 0;
        List<String> nullableColumns = new ArrayList<>();
        for (String columnName : columns) {
            ColumnModel column = table.column(columnName);
            if (column == null) {
                continue;
            }
            if (column.nullable()) {
                nullableCount++;
                nullableColumns.add(columnName);
            } else if (column.notNull()) {
                notNullCount++;
            }
        }
        if (nullableCount == 0 || notNullCount == 0) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " foreign key " + foreignKey.name() + " "
                + columns + " (referencing " + foreignKey.referencedQualifiedName() + ") mixes NOT NULL columns "
                + "with nullable columns " + nullableColumns + "; a NULL in any nullable column bypasses the "
                + "referential check for the whole constraint.");
    }
}
