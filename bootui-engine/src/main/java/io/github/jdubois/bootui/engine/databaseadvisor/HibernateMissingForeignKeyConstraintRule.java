package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;
import java.util.Locale;

/**
 * Cross-references a mapped association against the physical schema's foreign key constraints: the entity
 * model says "this column points at another table", but the database enforces nothing.
 *
 * <p>This is the referential-integrity twin of {@code DB-HIB-005}: without the constraint, an orphaned child
 * row is a normal insert as far as the database is concerned, and Hibernate will happily load an association
 * that resolves to nothing at runtime. It commonly happens when a schema was generated with
 * {@code hibernate.hbm2ddl.auto} settings or migrations that skip constraints, or after a table was recreated
 * without them.</p>
 *
 * <p>It only fires for associations whose join columns are fully resolved and physically present, and only
 * when the table's foreign key metadata was read completely — an unreadable constraint list is never treated
 * as an empty one.</p>
 */
final class HibernateMissingForeignKeyConstraintRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingForeignKeyConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-007",
                "Mapped association has no physical foreign key constraint",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @ManyToOne/@OneToOne @JoinColumn(s) — including composite ones — "
                        + "against the foreign keys DatabaseMetaData.getImportedKeys() reports for the same table.",
                "Add the foreign key constraint via a migration. Without it the database never rejects an "
                        + "orphaned child row, so an association the entity model presents as guaranteed can "
                        + "resolve to a missing row at runtime, and cascading deletes are silently not enforced.",
                "https://vladmihalcea.com/database-uniqueness-application-level-vs-database-level/"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            List<String> columns = foreignKey.columns();
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                continue;
            }
            if (!hasPhysicalForeignKey(table, columns)) {
                details.add(schema.dataSourceName() + ": " + foreignKey.attributeDescription() + " maps "
                        + table.qualifiedName() + " " + columns
                        + " as a foreign key, but the database enforces no such constraint.");
            }
        }
    }

    private boolean hasPhysicalForeignKey(TableModel table, List<String> columns) {
        return table.foreignKeys().stream().anyMatch(foreignKey -> sameColumns(foreignKey.columns(), columns));
    }

    private boolean sameColumns(List<String> physical, List<String> mapped) {
        if (physical.size() != mapped.size()) {
            return false;
        }
        for (int i = 0; i < physical.size(); i++) {
            String left = physical.get(i);
            String right = mapped.get(i);
            if (left == null
                    || right == null
                    || !left.toLowerCase(Locale.ROOT).equals(right.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }
}
