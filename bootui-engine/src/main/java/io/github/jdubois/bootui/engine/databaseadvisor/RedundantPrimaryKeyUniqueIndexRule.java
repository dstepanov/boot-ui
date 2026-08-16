package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * An explicit unique index that duplicates the primary key: same columns, in the same order, enforcing the
 * same guarantee the primary key's own backing index already provides.
 *
 * <p>The previous implementation compared column <em>sets</em> and assumed "the first of several matches is
 * the primary key's own index", which both missed the ordering requirement (a unique index on
 * {@code (b, a)} is a different access path from a primary key on {@code (a, b)}) and could name the real
 * primary key index as the redundant one. This version identifies the backing index explicitly — by the
 * {@code PK_NAME} the driver reports, falling back to the unique index covering exactly the primary key
 * columns in order — and only reports the other matches. Partial and expression indexes are excluded: they do
 * not enforce the same constraint.</p>
 */
final class RedundantPrimaryKeyUniqueIndexRule extends AbstractDatabaseAdvisorRule {

    RedundantPrimaryKeyUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-005",
                "Redundant unique index duplicating the primary key",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects unique indexes covering exactly the primary key's columns in the same order, other than "
                        + "the primary key's own backing index. Partial and expression indexes are excluded.",
                "Every additional unique index slows down INSERT/UPDATE/DELETE and consumes storage. When a "
                        + "unique index's columns exactly match the primary key's, it duplicates a guarantee the "
                        + "primary key's own backing index already enforces and can usually be dropped — after "
                        + "checking that no foreign key or application code references it by name.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().indexesRead() || !table.metadata().primaryKeyRead()) {
                    continue;
                }
                checkTable(schema, table, details);
            }
        }
        return violation(details);
    }

    private void checkTable(SchemaSnapshot schema, TableModel table, List<String> details) {
        List<String> primaryKeyColumns = table.primaryKeyColumns();
        if (primaryKeyColumns.isEmpty()) {
            return;
        }
        IndexModel backingIndex = table.primaryKeyBackingIndex();
        for (IndexModel index : table.indexes()) {
            if (index == backingIndex || !index.unique() || index.partial() || index.hasExpressionKeyPart()) {
                continue;
            }
            if (index.coversExactlyInOrder(primaryKeyColumns)) {
                details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " unique index " + index.name()
                        + " " + index.describeKeyParts() + " duplicates the primary key columns "
                        + primaryKeyColumns + ".");
            }
        }
    }
}
