package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;

/**
 * Cross-references the Hibernate metamodel against the physical schema: a mapped {@code @ManyToOne}/
 * {@code @OneToOne} foreign key whose join columns have no supporting physical index.
 *
 * <p>Unlike the Hibernate Advisor's own {@code HIB-MAP-019} (which only sees JPA-declared
 * {@code @Table(indexes=...)} metadata), this rule sees the database's actual indexes — including ones created
 * by a Flyway/Liquibase migration — so it only fires when the physical schema genuinely has no supporting
 * index.</p>
 *
 * <p>Composite associations are evaluated as a unit: a {@code @JoinColumns} pair needs an index leading on
 * both columns in declaration order, and the index must be genuinely usable (not invalid, invisible, partial,
 * expression-based or prefix-truncated), matching {@code DB-SCHEMA-002}'s semantics.</p>
 *
 * <p>An association whose join columns already match a physical foreign key constraint is skipped here: every
 * physical foreign key is independently evaluated by {@code DB-SCHEMA-002}, so reporting the same missing
 * index again from the mapping side would double-count one problem as two findings. This rule keeps its
 * distinct value for the case {@code DB-SCHEMA-002} cannot see at all — a mapped association with
 * <em>no</em> physical foreign key constraint, unindexed or not.</p>
 */
final class HibernateMissingForeignKeyIndexRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingForeignKeyIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-001",
                "Mapped foreign key column has no physical index",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references mapped @ManyToOne/@OneToOne @JoinColumn(s) foreign keys — including composite "
                        + "ones — against the physical schema's actual usable indexes, not just JPA-declared "
                        + "@Table(indexes=...) metadata. Skipped when a physical foreign key constraint already "
                        + "covers the same columns, since DB-SCHEMA-002 evaluates that index independently.",
                "Add a database index (via a migration) leading on the mapped foreign key column(s), in the same "
                        + "order. Hibernate loads the association's target through those columns on every "
                        + "traversal, and cascading deletes/updates on the parent row scan the child table "
                        + "without it.",
                "https://vladmihalcea.com/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/"));
    }

    @Override
    void checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details) {
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            MappedTableResolution resolution = resolveItemTable(context, entity, primary, foreignKey.tableName());
            if (!resolution.resolved()) {
                continue;
            }
            TableModel table = resolution.table();
            List<String> columns = foreignKey.columns();
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                // A join column that does not exist physically is DB-HIB-007's finding, not this rule's.
                continue;
            }
            if (ForeignKeyMatching.anyForeignKeyCoversColumnSet(table, columns)) {
                // DB-SCHEMA-002 already evaluates this exact physical foreign key's index coverage.
                continue;
            }
            boolean supported = resolution.schema().dialect() == Dialect.ORACLE
                    ? table.hasUsableLeadingIndexAnyOrder(columns)
                    : table.hasUsableLeadingIndex(columns);
            if (!supported) {
                details.add(resolution.schema().dataSourceName() + ": " + foreignKey.attributeDescription()
                        + " maps foreign key column(s) " + table.qualifiedName() + " " + columns
                        + ", which have no usable leading index in the physical schema.");
            }
        }
    }
}
