package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * Compact builders for the physical-schema model, so a rule test reads as the schema it describes rather than
 * as a wall of record constructor arguments.
 */
final class DatabaseAdvisorFixtures {

    private DatabaseAdvisorFixtures() {}

    static SchemaSnapshot schema(String name, Dialect dialect, List<TableModel> tables) {
        return schema(name, dialect, tables, VendorFindings.EMPTY);
    }

    static SchemaSnapshot schema(String name, Dialect dialect, List<TableModel> tables, VendorFindings findings) {
        return new SchemaSnapshot(
                name,
                dialect,
                dialect.label(),
                DatabaseVersion.of(15, 0, "15.0"),
                "LOWER",
                tables,
                findings,
                List.of(),
                false,
                null);
    }

    static SchemaSnapshot vendorSchema(String name, Dialect dialect, VendorAugmentation<?> augmentation) {
        return schema(
                name,
                dialect,
                List.of(),
                VendorFindings.builder().add(augmentation).build());
    }

    static DatabaseAdvisorContext context(List<SchemaSnapshot> schemas) {
        return new DatabaseAdvisorContext(schemas, false, List.of());
    }

    static DatabaseAdvisorContext context(SchemaSnapshot schema) {
        return context(List.of(schema));
    }

    static TableModel table(
            String name,
            List<ColumnModel> columns,
            List<String> primaryKeyColumns,
            List<ForeignKeyModel> foreignKeys,
            List<IndexModel> indexes) {
        return TableModel.of("app", "public", name, columns, primaryKeyColumns, foreignKeys, indexes);
    }

    static ColumnModel column(String name, String typeName, int jdbcType) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NULLABLE, null, null, false);
    }

    static ColumnModel column(String name, String typeName, int jdbcType, Integer size) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NULLABLE, size, null, false);
    }

    static ColumnModel notNullColumn(String name, String typeName, int jdbcType) {
        return new ColumnModel(name, typeName, jdbcType, ColumnModel.Nullability.NOT_NULL, null, null, false);
    }

    static IndexModel index(String name, List<String> columns) {
        return IndexModel.of(name, columns, false);
    }

    static IndexModel uniqueIndex(String name, List<String> columns) {
        return IndexModel.of(name, columns, true);
    }

    static IndexModel prefixIndex(String name, String column, int prefixLength, boolean unique) {
        return new IndexModel(
                name,
                List.of(new IndexKeyPart(column, null, true, prefixLength, null)),
                unique,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel partialIndex(String name, List<String> columns, String predicate) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                predicate,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel invisibleIndex(String name, List<String> columns) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                null,
                IndexModel.Visibility.INVISIBLE,
                IndexModel.Validity.VALID);
    }

    static IndexModel invalidIndex(String name, List<String> columns) {
        return new IndexModel(
                name,
                columns.stream()
                        .map(column -> IndexKeyPart.column(column, true))
                        .toList(),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.INVALID);
    }

    static IndexModel expressionIndex(String name, String expression) {
        return new IndexModel(
                name,
                List.of(IndexKeyPart.expression(expression)),
                false,
                "btree",
                null,
                IndexModel.Visibility.VISIBLE,
                IndexModel.Validity.VALID);
    }

    static ForeignKeyModel foreignKey(
            String name, List<String> columns, String referencedTable, List<String> referencedColumns) {
        return new ForeignKeyModel(name, columns, "app", "public", referencedTable, referencedColumns);
    }
}
