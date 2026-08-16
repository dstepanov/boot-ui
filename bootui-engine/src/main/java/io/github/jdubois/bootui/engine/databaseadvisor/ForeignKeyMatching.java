package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared logic for matching a mapped {@code @JoinColumn}/{@code @JoinColumns} association against a table's
 * physical foreign key constraints ({@code DatabaseMetaData.getImportedKeys()}).
 *
 * <p>Column order needs different tolerance in each direction. A constraint's own DDL column order does not
 * decide whether it exists — {@code FOREIGN KEY (b, a) REFERENCES parent (pb, pa)} is the same constraint as
 * one declared {@code (a, b) REFERENCES parent (pa, pb)} — but the <em>pairing</em> between a child column and
 * the parent column it actually references does matter: reordering so a child column pairs with a different
 * parent column is a genuinely different (and broken) mapping, not a formatting difference.</p>
 */
final class ForeignKeyMatching {

    private ForeignKeyMatching() {}

    /**
     * True when some physical foreign key on {@code table} covers exactly {@code columns} (same size, same
     * column names, order-independent). Used only to tell whether {@code DB-SCHEMA-002} already independently
     * evaluates this foreign key's index coverage from the physical side, so {@code DB-HIB-001} does not
     * double-count the same missing index: every physical foreign key is evaluated by {@code DB-SCHEMA-002}
     * regardless of any Hibernate mapping, so skipping here can only remove a duplicate, never a gap.
     */
    static boolean anyForeignKeyCoversColumnSet(TableModel table, List<String> columns) {
        return table.foreignKeys().stream().anyMatch(foreignKey -> sameColumnSet(foreignKey.columns(), columns));
    }

    /**
     * True when a physical foreign key constraint on {@code table} genuinely implements {@code foreignKey}:
     * every mapped join column is present among the constraint's own columns, every explicitly declared
     * {@code referencedColumnName} pairs with the same parent column the constraint pairs it with, and — when
     * the association's target entity table is resolvable — the constraint references that same table. A
     * join column with no declared {@code referencedColumnName} is not pair-checked: JPA resolves it implicitly
     * (normally to the target's primary key column), which this bridge does not guess.
     */
    static boolean hasMatchingPhysicalForeignKey(TableModel table, MappedForeignKeyFacts foreignKey) {
        return table.foreignKeys().stream().anyMatch(physical -> matches(physical, foreignKey));
    }

    private static boolean matches(ForeignKeyModel physical, MappedForeignKeyFacts mapped) {
        List<String> mappedColumns = mapped.columns();
        if (physical.columns().size() != mappedColumns.size() || !sameColumnSet(physical.columns(), mappedColumns)) {
            return false;
        }
        if (mapped.targetTableResolved() && !referencesTarget(physical, mapped)) {
            return false;
        }
        List<String> referencedColumns = mapped.referencedColumns();
        for (int i = 0; i < mappedColumns.size(); i++) {
            String expectedParent = i < referencedColumns.size() ? referencedColumns.get(i) : null;
            if (expectedParent == null) {
                continue;
            }
            int physicalIndex = indexOfIgnoreCase(physical.columns(), mappedColumns.get(i));
            String actualParent = physicalIndex >= 0
                            && physicalIndex < physical.referencedColumns().size()
                    ? physical.referencedColumns().get(physicalIndex)
                    : null;
            if (!equalsIgnoreCase(actualParent, expectedParent)) {
                return false;
            }
        }
        return true;
    }

    private static boolean referencesTarget(ForeignKeyModel physical, MappedForeignKeyFacts mapped) {
        if (!equalsIgnoreCase(physical.referencedTable(), mapped.targetTableName())) {
            return false;
        }
        return matchesQualifier(physical.referencedSchema(), mapped.targetSchema())
                && matchesQualifier(physical.referencedCatalog(), mapped.targetCatalog());
    }

    private static boolean matchesQualifier(String actual, String declared) {
        if (declared == null || declared.isBlank()) {
            return true;
        }
        return equalsIgnoreCase(actual, declared);
    }

    private static boolean sameColumnSet(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        List<String> remaining = new ArrayList<>(left);
        for (String column : right) {
            if (!remaining.removeIf(candidate -> equalsIgnoreCase(candidate, column))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfIgnoreCase(List<String> columns, String value) {
        for (int i = 0; i < columns.size(); i++) {
            if (equalsIgnoreCase(columns.get(i), value)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equalsIgnoreCase(String left, String right) {
        return left != null && right != null && left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT));
    }
}
