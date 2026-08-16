package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * A composite (multi-column) unique index where some, but not all, of its columns are nullable — and the
 * database does not treat {@code NULL} as distinct-from-nothing across the whole key (PostgreSQL's
 * {@code NULLS NOT DISTINCT}, 15+, is the only dialect this advisor can confirm that for).
 *
 * <p>Standard SQL unique-index semantics compare a composite key as a whole: as soon as <em>any</em> one
 * column in the row is {@code NULL}, the whole row is treated as distinct from every other row for that
 * index — including ones whose {@code NOT NULL} columns hold the exact same values. A unique index on
 * {@code (org_id NOT NULL, external_ref NULLABLE)} therefore does not limit an organization to one row with a
 * given {@code external_ref}; it allows unlimited rows with the <em>same</em> {@code org_id} as long as
 * {@code external_ref} is {@code NULL} each time, which usually defeats the "at most one" guarantee the
 * constraint appears to make. A composite key that is fully {@code NOT NULL} (fully enforced) or fully
 * nullable (uniqueness genuinely only matters when every part is present) is not flagged — only the mixed
 * case, where the gap exists regardless of what the schema author intended.</p>
 *
 * <p>Only columns whose nullability was read with certainty count either way, and an index PostgreSQL 15+
 * declared {@code NULLS NOT DISTINCT} is never flagged: it closes exactly this gap by treating {@code NULL}
 * as an ordinary, comparable value across the whole key.</p>
 */
final class NullableColumnCompositeUniquenessRule extends AbstractDatabaseAdvisorRule {

    NullableColumnCompositeUniquenessRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-009",
                "Composite unique index with partially nullable columns",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Detects a usable multi-column unique index where at least one column is definitely nullable "
                        + "and at least one other is definitely NOT NULL, and the index is not declared NULLS "
                        + "NOT DISTINCT (PostgreSQL 15+). Columns with unknown nullability are not counted "
                        + "either way.",
                "Either make every column in the unique index NOT NULL, declare the index NULLS NOT DISTINCT "
                        + "(PostgreSQL 15+), or, if application logic must tolerate several NULLs in that column, "
                        + "document that the constraint only applies when every column is present. Standard SQL "
                        + "unique-index semantics compare a composite key as a whole, so a NULL in any nullable "
                        + "column lets an otherwise-duplicate row through.",
                "https://www.postgresql.org/docs/current/indexes-unique.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            if (!supportsNullDistinctUniqueness(schema.dialect())) {
                continue;
            }
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().indexesRead() || !table.metadata().columnsRead()) {
                    continue;
                }
                checkTable(schema, table, details);
            }
        }
        return violation(details);
    }

    private static boolean supportsNullDistinctUniqueness(Dialect dialect) {
        return dialect == Dialect.POSTGRESQL || dialect.isMySqlFamily() || dialect == Dialect.ORACLE;
    }

    private void checkTable(SchemaSnapshot schema, TableModel table, List<String> details) {
        for (IndexModel index : table.indexes()) {
            if (!index.unique()
                    || !index.usable()
                    || index.nullsNotDistinct()
                    || index.keyParts().size() < 2) {
                continue;
            }
            checkIndex(schema, table, index, details);
        }
    }

    private void checkIndex(SchemaSnapshot schema, TableModel table, IndexModel index, List<String> details) {
        int nullableCount = 0;
        int notNullCount = 0;
        List<String> nullableColumns = new ArrayList<>();
        for (IndexKeyPart keyPart : index.keyParts()) {
            if (keyPart.isExpression()) {
                continue;
            }
            ColumnModel column = table.column(keyPart.columnName());
            if (column == null) {
                continue;
            }
            if (column.nullable()) {
                nullableCount++;
                nullableColumns.add(keyPart.columnName());
            } else if (column.notNull()) {
                notNullCount++;
            }
        }
        if (nullableCount == 0 || notNullCount == 0) {
            return;
        }
        details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " unique index " + index.name() + " "
                + index.describeKeyParts() + " mixes NOT NULL columns with nullable columns " + nullableColumns
                + "; a NULL in any nullable column lets an otherwise-duplicate row through.");
    }
}
