package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Two separate foreign key constraints on the same table that enforce the exact same referential
 * relationship: the same child columns, referencing the same parent table through the same parent columns.
 *
 * <p>Column order does not decide whether two constraints are duplicates — a constraint's own DDL column
 * order does not change which child column is paired with which parent column — so this compares the
 * <em>set</em> of {@code (child column, parent column)} pairs each constraint's {@code KEY_SEQ} order
 * establishes, not the raw column lists positionally. Two constraints with the same child columns but
 * different parent columns (a rare but legal case) are correctly treated as different relationships, not
 * duplicates.</p>
 *
 * <p>Every insert, update, and delete on the child table pays the referential-integrity check twice for no
 * additional guarantee, and cascading actions on the parent side run twice as well.</p>
 */
final class DuplicateForeignKeyRule extends AbstractDatabaseAdvisorRule {

    DuplicateForeignKeyRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-006",
                "Duplicate foreign key constraints",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects two foreign key constraints on the same table (from DatabaseMetaData.getImportedKeys())"
                        + " whose child-to-parent column pairs are identical, referencing the same parent "
                        + "table.",
                "Drop the redundant constraint (checking first that no application code or tooling references "
                        + "it by name). Every insert/update/delete pays the referential-integrity check for both "
                        + "constraints with no additional guarantee from the second one.",
                "https://vladmihalcea.com/database-table-relationships/"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().foreignKeysRead()) {
                    continue;
                }
                checkTable(schema, table, details);
            }
        }
        return violation(details);
    }

    private void checkTable(SchemaSnapshot schema, TableModel table, List<String> details) {
        List<ForeignKeyModel> foreignKeys = table.foreignKeys();
        for (int i = 0; i < foreignKeys.size(); i++) {
            ForeignKeyModel first = foreignKeys.get(i);
            if (!first.consistent()) {
                continue;
            }
            for (int j = i + 1; j < foreignKeys.size(); j++) {
                ForeignKeyModel second = foreignKeys.get(j);
                if (!second.consistent() || !sameRelationship(first, second)) {
                    continue;
                }
                details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " foreign keys "
                        + first.name() + " and " + second.name() + " both enforce " + first.columns()
                        + " referencing " + first.referencedQualifiedName() + " " + first.referencedColumns()
                        + "; one of them is redundant.");
            }
        }
    }

    /** True when both constraints reference the same parent table through the identical set of column pairs. */
    private boolean sameRelationship(ForeignKeyModel first, ForeignKeyModel second) {
        if (first.columns().size() != second.columns().size()) {
            return false;
        }
        if (!equalsIgnoreCase(first.referencedTable(), second.referencedTable())
                || !equalsIgnoreCase(first.referencedSchema(), second.referencedSchema())
                || !equalsIgnoreCase(first.referencedCatalog(), second.referencedCatalog())) {
            return false;
        }
        return columnPairs(first).equals(columnPairs(second));
    }

    private Set<String> columnPairs(ForeignKeyModel foreignKey) {
        Set<String> pairs = new HashSet<>();
        for (int i = 0; i < foreignKey.columns().size(); i++) {
            String child = normalize(foreignKey.columns().get(i));
            String parent = normalize(foreignKey.referencedColumns().get(i));
            pairs.add(child + "->" + parent);
        }
        return pairs;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.equalsIgnoreCase(right);
    }
}
