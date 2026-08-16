package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedUniqueConstraintFacts;
import java.util.List;

/**
 * Cross-references a mapped unique constraint — a single-column {@code @Column(unique=true)} attribute or a
 * multi-column {@code @Table(uniqueConstraints=...)} constraint — against the uniqueness the database actually
 * enforces.
 *
 * <p>"Actually enforces" is stricter than "there is a unique index with those column names": a MySQL prefix
 * index ({@code unique key (email(20))}) enforces uniqueness of the first twenty characters, not of the
 * column, so two different long values can still collide or be wrongly rejected; a partial index only
 * constrains the rows matching its predicate; an expression index constrains the expression, not the column;
 * and an invalid or invisible index constrains nothing the planner will honor. Column <em>order</em> is
 * ignored, because uniqueness over {@code (a, b)} and {@code (b, a)} is the same guarantee — only the access
 * path differs.</p>
 */
final class HibernateMissingUniqueIndexRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingUniqueIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-005",
                "Mapped unique constraint has no backing physical unique index",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @Column(unique=true) attributes and @Table(uniqueConstraints=...) "
                        + "constraints against the physical unique indexes that genuinely cover the same columns. "
                        + "Prefix, partial, expression, invalid and invisible indexes do not count as coverage.",
                "Add a unique index or constraint (via a migration) covering the same column(s) in full. Without "
                        + "one, the database never enforces the mapping's uniqueness assumption, so concurrent "
                        + "inserts can create duplicate rows the application logic never expected.",
                "https://vladmihalcea.com/database-uniqueness-application-level-vs-database-level/"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedUniqueConstraintFacts uniqueConstraint : entity.uniqueConstraints()) {
            List<String> columns = uniqueConstraint.columns();
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                continue;
            }
            if (isPrimaryKey(table, columns) || table.hasEnforcedUniqueness(columns)) {
                continue;
            }
            details.add(schema.dataSourceName() + ": " + uniqueConstraint.description()
                    + " declares a unique constraint on " + table.qualifiedName() + " " + columns
                    + ", which no physical unique index fully enforces.");
        }
    }

    /** The primary key already enforces uniqueness over its own columns, whatever the index catalog shows. */
    private boolean isPrimaryKey(TableModel table, List<String> columns) {
        List<String> primaryKeyColumns = table.primaryKeyColumns();
        if (primaryKeyColumns.size() != columns.size() || primaryKeyColumns.isEmpty()) {
            return false;
        }
        return columns.stream()
                .allMatch(column -> primaryKeyColumns.stream()
                        .anyMatch(primaryKeyColumn -> primaryKeyColumn != null
                                && column != null
                                && primaryKeyColumn.equalsIgnoreCase(column)));
    }
}
