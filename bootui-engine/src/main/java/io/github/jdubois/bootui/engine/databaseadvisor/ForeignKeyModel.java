package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * One physical foreign key read from {@code DatabaseMetaData.getImportedKeys}, with its own columns and the
 * columns it actually references, both in {@code KEY_SEQ} order.
 *
 * <p>The referenced side carries the qualified identity ({@code PKTABLE_CAT}/{@code PKTABLE_SCHEM}) and the
 * real {@code PKCOLUMN_NAME} list, because a foreign key may reference an alternate unique key rather than
 * the referenced table's primary key — comparing against the primary key positionally (the previous
 * behavior) reports mismatches that do not exist.</p>
 *
 * @param name the constraint name, or a synthetic id for an unnamed constraint
 * @param columns the referencing (child) columns in key sequence order
 * @param referencedCatalog the referenced table's catalog, or {@code null}
 * @param referencedSchema the referenced table's schema, or {@code null}
 * @param referencedTable the referenced table name
 * @param referencedColumns the referenced (parent) columns in key sequence order
 */
record ForeignKeyModel(
        String name,
        List<String> columns,
        String referencedCatalog,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns) {

    ForeignKeyModel {
        columns = List.copyOf(columns);
        referencedColumns = List.copyOf(referencedColumns);
    }

    String referencedQualifiedName() {
        return referencedSchema == null || referencedSchema.isBlank()
                ? String.valueOf(referencedTable)
                : referencedSchema + "." + referencedTable;
    }

    /** True when the driver reported the same number of referencing and referenced columns. */
    boolean consistent() {
        return !columns.isEmpty() && columns.size() == referencedColumns.size();
    }
}
