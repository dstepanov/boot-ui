package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedForeignKeyFacts;
import java.util.List;

/**
 * Cross-references every explicitly-named mapped column — basic {@code @Column(name=...)} attributes and
 * {@code @JoinColumn(s)} join columns — against the columns the table actually has.
 *
 * <p>A mapped column that does not exist physically is not a style question: every query touching that
 * attribute fails at runtime with "column does not exist", usually only on the code path that first selects
 * it. It normally means a migration was never applied, was applied to a different schema, or the entity is
 * ahead of the database.</p>
 *
 * <p>Only explicit names are checked (the bridge never guesses a naming strategy), and only tables whose
 * column metadata was read completely — a truncated column list would make every unread column look missing.
 * Hibernate's own {@code ddl-auto} validation covers the same ground at startup, but it is off in most
 * applications and it fails the boot instead of reporting; this reports it as a finding against the live
 * schema.</p>
 */
final class HibernateMissingColumnRule extends AbstractHibernateCrossReferenceRule {

    HibernateMissingColumnRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-006",
                "Mapped column not found in the physical table",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.HIGH,
                "Cross-references explicitly named @Column(name=...) attributes and @JoinColumn(s) join columns "
                        + "against DatabaseMetaData.getColumns() for the resolved physical table.",
                "Apply the missing migration, or correct the mapping. Every query touching a mapped column that "
                        + "does not exist fails at runtime with a \"column does not exist\" error, typically only "
                        + "on the code path that first selects it.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedColumnFacts column : entity.columns()) {
            if (!table.hasColumn(column.columnName())) {
                details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " maps column "
                        + table.qualifiedName() + "." + column.columnName()
                        + ", which does not exist in the physical table.");
            }
        }
        for (MappedForeignKeyFacts foreignKey : entity.foreignKeys()) {
            for (String column : foreignKey.columns()) {
                if (!table.hasColumn(column)) {
                    details.add(schema.dataSourceName() + ": " + foreignKey.attributeDescription()
                            + " maps join column " + table.qualifiedName() + "." + column
                            + ", which does not exist in the physical table.");
                }
            }
        }
    }
}
