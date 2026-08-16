package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;

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
 * as an empty one. An association explicitly declaring {@code @JoinColumn(foreignKey = @ForeignKey(
 * ConstraintMode.NO_CONSTRAINT))} is skipped entirely: the absence of a physical constraint there is the
 * developer's own choice, not a defect. Matching a candidate physical constraint tolerates the constraint's
 * own DDL column order (which does not decide whether it is the same constraint) but not a different
 * child-to-parent column pairing, and — when the target entity's table is resolvable — requires the
 * constraint to actually reference that table, so a same-named-columns constraint pointing at an unrelated
 * table is never mistaken for this association's.</p>
 */
final class HibernateMissingForeignKeyConstraintRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingForeignKeyConstraintRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-007",
                "Mapped association has no physical foreign key constraint",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @ManyToOne/@OneToOne @JoinColumn(s) — including composite ones — "
                        + "against the foreign keys DatabaseMetaData.getImportedKeys() reports for the same table, "
                        + "verifying the child-to-parent column pairing and (when resolvable) the referenced "
                        + "table. Associations declaring @ForeignKey(ConstraintMode.NO_CONSTRAINT) are skipped.",
                "Add the foreign key constraint via a migration. Without it the database never rejects an "
                        + "orphaned child row, so an association the entity model presents as guaranteed can "
                        + "resolve to a missing row at runtime, and cascading deletes are silently not enforced.",
                "https://vladmihalcea.com/database-table-relationships/"));
    }

    @Override
    void checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            if (!foreignKey.constraintExpected()) {
                continue;
            }
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, foreignKey.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            List<String> columns = foreignKey.columns();
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                continue;
            }
            if (!ForeignKeyMatching.hasMatchingPhysicalForeignKey(table, foreignKey)) {
                details.add(resolution.schema().dataSourceName() + ": " + foreignKey.attributeDescription()
                        + " maps " + table.qualifiedName() + " " + columns
                        + " as a foreign key, but the database enforces no such constraint.");
            }
        }
    }
}
