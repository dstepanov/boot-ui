package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * The physical schema read from one {@code DataSource}, plus every vendor catalog augmentation attempted for
 * it, the diagnostics collected on the way, and whether a bound cut the scan short.
 *
 * <p>A datasource that could not be introspected at all is represented with an empty {@link #tables()} and a
 * non-null {@link #error()} (already credential-redacted). A datasource that was introspected but whose
 * metadata is incomplete keeps its readable tables and reports the gaps through {@link #diagnostics()} —
 * partial data is still useful, silence is not.</p>
 */
record SchemaSnapshot(
        String dataSourceName,
        Dialect dialect,
        String databaseProductName,
        DatabaseVersion version,
        String identifierCase,
        List<TableModel> tables,
        VendorFindings vendorFindings,
        List<SchemaDiagnostic> diagnostics,
        boolean truncated,
        String error) {

    SchemaSnapshot {
        tables = List.copyOf(tables);
        diagnostics = List.copyOf(diagnostics);
    }

    static SchemaSnapshot failed(String dataSourceName, String error) {
        SchemaDiagnostic diagnostic = SchemaDiagnostic.error(dataSourceName, error);
        return new SchemaSnapshot(
                dataSourceName,
                Dialect.GENERIC,
                null,
                DatabaseVersion.UNKNOWN,
                null,
                List.of(),
                VendorFindings.EMPTY,
                List.of(diagnostic),
                false,
                diagnostic.message());
    }

    boolean available() {
        return error == null;
    }

    /** True when this snapshot is complete: nothing truncated, nothing that failed to read. */
    boolean complete() {
        return available()
                && !truncated
                && diagnostics.stream()
                        .noneMatch(diagnostic -> SchemaDiagnostic.ERROR.equals(diagnostic.level())
                                || SchemaDiagnostic.WARNING.equals(diagnostic.level()));
    }

    String describeProduct() {
        if (databaseProductName == null || databaseProductName.isBlank()) {
            return dialect.label();
        }
        return version.known()
                ? databaseProductName + " " + version.major() + "." + version.minor()
                : databaseProductName;
    }

    /** The first table matching {@code tableName} in any schema, or {@code null}. */
    TableModel table(String tableName) {
        return table(null, null, tableName);
    }

    /** The first table matching an optionally qualified catalog/schema/name, or {@code null}. */
    TableModel table(String catalog, String schema, String tableName) {
        return tables.stream()
                .filter(table -> table.matches(catalog, schema, tableName))
                .findFirst()
                .orElse(null);
    }

    /** Every table matching {@code tableName}, across schemas — used to detect ambiguous matches. */
    List<TableModel> tablesNamed(String catalog, String schema, String tableName) {
        return tables.stream()
                .filter(table -> table.matches(catalog, schema, tableName))
                .toList();
    }
}
