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
                        + "@Table(indexes=...) metadata.",
                "Add a database index (via a migration) leading on the mapped foreign key column(s), in the same "
                        + "order. Hibernate loads the association's target through those columns on every "
                        + "traversal, and cascading deletes/updates on the parent row scan the child table "
                        + "without it.",
                "https://vladmihalcea.com/how-to-map-a-onetomany-jpa-and-hibernate-association/"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            List<String> columns = foreignKey.columns();
            if (columns.isEmpty() || !columns.stream().allMatch(table::hasColumn)) {
                // A join column that does not exist physically is DB-HIB-007's finding, not this rule's.
                continue;
            }
            if (!table.hasUsableLeadingIndex(columns)) {
                details.add(schema.dataSourceName() + ": " + foreignKey.attributeDescription()
                        + " maps foreign key column(s) " + table.qualifiedName() + " " + columns
                        + ", which have no usable leading index in the physical schema.");
            }
        }
    }
}
