package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedColumnFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/**
 * Cross-references an explicitly declared {@code @Column(length=...)} against the physical column's reported
 * size: when the entity permits a longer string than the column can hold, an insert either silently truncates
 * (on a lenient database) or fails with a data-truncation error.
 *
 * <p>Two sources of false positives are removed. Only an <em>explicitly declared</em> length is compared —
 * JPA's invisible default of 255 is not a statement of intent, and comparing it flagged every deliberately
 * narrow column. And only columns with a genuinely bounded physical size are compared: an {@code @Lob}, a
 * {@code text}/{@code clob} column, or any column whose driver reports no usable size (PostgreSQL reports
 * {@code 2147483647} for unbounded {@code text}) is skipped rather than measured against a number that does
 * not mean what it looks like.</p>
 */
final class HibernateColumnLengthMismatchRule extends AbstractHibernateCrossReferenceRule {

    /**
     * Sizes at or above this are the drivers' way of saying "unbounded" for text/CLOB columns, not a real
     * declared width.
     */
    private static final int UNBOUNDED_SIZE = 1_000_000;

    HibernateColumnLengthMismatchRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-004",
                "Mapped column length longer than the physical column size",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references explicitly declared @Column(length=...) attributes against the physical "
                        + "string/char column's reported size. Attributes without an explicit length, @Lob "
                        + "attributes, and columns with no bounded physical size are not compared.",
                "Align the entity's @Column(length=...) with the physical column size, or widen the physical "
                        + "column via a migration. A mapping that permits more characters than the database column "
                        + "can hold either silently truncates input or fails with a data-truncation error, "
                        + "depending on the database's strictness.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details) {
        for (MappedColumnFacts column : entity.columns()) {
            checkColumn(schema, table, column, details);
        }
    }

    private void checkColumn(SchemaSnapshot schema, TableModel table, MappedColumnFacts column, List<String> details) {
        Integer declaredLength = column.declaredLength();
        if (declaredLength == null || column.lob()) {
            return;
        }
        ColumnModel physical = table.column(column.columnName());
        if (physical == null || JdbcTypeFamily.of(physical) != JdbcTypeFamily.STRING) {
            return;
        }
        Integer size = physical.size();
        if (size == null || size <= 0 || size >= UNBOUNDED_SIZE) {
            return;
        }
        if (size < declaredLength) {
            details.add(schema.dataSourceName() + ": " + column.attributeDescription() + " declares @Column(length="
                    + declaredLength + "), which is longer than physical column " + table.qualifiedName() + "."
                    + column.columnName() + " (" + physical.describeType() + "), a truncation risk.");
        }
    }
}
