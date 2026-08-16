package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/**
 * Cross-references mapped {@code @Column(name=...)} attributes against the physical column: a coarse
 * type-family mismatch (a {@code String} attribute on a numeric column) or a nullability disagreement, both
 * of which surface at runtime as a conversion failure or a constraint violation rather than at compile time.
 *
 * <p>Both halves are deliberately narrowed to what the mapping actually states:</p>
 *
 * <ul>
 *   <li><strong>Type</strong> comparison is skipped whenever an {@code @Convert}, an {@code @Enumerated} or an
 *       {@code @Lob} decides the persisted shape — a converter legitimately stores a {@code String} in an
 *       {@code int} column, and an enum can be either — and whenever the physical column's type cannot be
 *       classified confidently.</li>
 *   <li><strong>Nullability</strong> is compared only when the attribute declares {@code @Column(nullable)}
 *       explicitly. JPA defaults {@code nullable} to {@code true}, so the previous behavior reported every
 *       {@code NOT NULL} column whose attribute simply never mentioned nullability — advice the developer
 *       cannot act on and which contradicts idiomatic Hibernate mappings.</li>
 * </ul>
 */
final class HibernateColumnMismatchRule extends AbstractHibernateCrossReferenceRule {

    HibernateColumnMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-003",
                "Mapped column type/nullability mismatch",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references mapped @Column(name=...) attributes against the physical column's JDBC type "
                        + "family and nullability. Converter/@Enumerated/@Lob attributes are skipped for the type "
                        + "comparison, and nullability is only compared when @Column(nullable=...) is explicit.",
                "Align the entity mapping with the physical column: a coarse type-family mismatch (text vs. "
                        + "numeric vs. date/time) usually fails at read/conversion time, and a NOT NULL column "
                        + "explicitly mapped as nullable can throw a constraint violation only under specific code "
                        + "paths.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedColumnFacts column : entity.columns()) {
            ColumnModel physical = table.column(column.columnName());
            if (physical == null) {
                // A mapped column with no physical counterpart is DB-HIB-006's finding.
                continue;
            }
            checkTypeFamily(schema, table, column, physical, details);
            checkNullability(schema, table, column, physical, details);
        }
    }

    private void checkTypeFamily(
            SchemaSnapshot schema,
            TableModel table,
            MappedColumnFacts column,
            ColumnModel physical,
            List<String> details) {
        if (column.ambiguousType()) {
            return;
        }
        JdbcTypeFamily javaFamily = JdbcTypeFamily.ofJavaType(column.javaTypeSimpleName());
        JdbcTypeFamily columnFamily = JdbcTypeFamily.of(physical);
        if (javaFamily == JdbcTypeFamily.OTHER || columnFamily == JdbcTypeFamily.OTHER || javaFamily == columnFamily) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " ("
                + column.javaTypeSimpleName() + ") maps column " + table.qualifiedName() + "." + column.columnName()
                + " (" + physical.describeType() + "), a type-family mismatch.");
    }

    private void checkNullability(
            SchemaSnapshot schema,
            TableModel table,
            MappedColumnFacts column,
            ColumnModel physical,
            List<String> details) {
        if (column.nullable() == null || physical.nullability() == ColumnModel.Nullability.UNKNOWN) {
            return;
        }
        if (physical.notNull() && column.nullable()) {
            details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " maps column "
                    + table.qualifiedName() + "." + column.columnName()
                    + ", which is NOT NULL in the database but is explicitly mapped as nullable.");
        } else if (physical.nullable() && !column.nullable()) {
            details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " maps column "
                    + table.qualifiedName() + "." + column.columnName()
                    + ", which allows NULL in the database but is explicitly mapped as non-nullable.");
        }
    }
}
