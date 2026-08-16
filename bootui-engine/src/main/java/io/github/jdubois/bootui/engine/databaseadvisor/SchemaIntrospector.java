package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Reads the physical schema of one {@code DataSource} through plain {@code java.sql.DatabaseMetaData} —
 * tables, columns, primary keys, foreign keys, and indexes — and augments it, for PostgreSQL and
 * MySQL/MariaDB, with the read-only catalog facts the generic JDBC API cannot answer.
 *
 * <p>This is purely read-only: it never executes DDL and never queries application data, only driver catalog
 * metadata and system-catalog rows. Three properties matter as much as the data itself:</p>
 *
 * <ul>
 *   <li><strong>Bounded.</strong> Every list is read one row past its bound so truncation is detected
 *       deterministically, every catalog statement carries a query timeout clamped to the remaining scan
 *       budget, and the scan stops between tables once the budget is spent — keeping whatever it already
 *       read.</li>
 *   <li><strong>Honest.</strong> A failure introspecting one datasource, one table, or one catalog
 *       augmentation is recorded as a diagnostic and leaves the rest of the scan intact; nothing that failed
 *       is ever presented as a clean result.</li>
 *   <li><strong>Non-invasive.</strong> The connection's original read-only flag is restored before it goes
 *       back to the pool, and no other connection state is touched.</li>
 * </ul>
 */
final class SchemaIntrospector {

    private static final String[] TABLE_TYPES = {"TABLE", "PARTITIONED TABLE"};
    private static final String[] FALLBACK_TABLE_TYPES = {"TABLE"};

    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema",
            "pg_catalog",
            "pg_toast",
            "mysql",
            "performance_schema",
            "sys",
            "sys_config",
            "innodb");

    private SchemaIntrospector() {}

    static SchemaSnapshot introspect(String dataSourceName, DataSource dataSource) {
        return introspect(
                dataSourceName,
                dataSource,
                ScanBudget.of(DatabaseAdvisorLimits.DEFAULTS.scanBudget()),
                DatabaseAdvisorLimits.DEFAULTS);
    }

    static SchemaSnapshot introspect(
            String dataSourceName, DataSource dataSource, ScanBudget budget, DatabaseAdvisorLimits limits) {
        if (dataSource == null) {
            return SchemaSnapshot.failed(dataSourceName, "DataSource bean is not available.");
        }
        return introspect(dataSourceName, dataSource::getConnection, budget, limits);
    }

    /** Test seam: the same introspection over any source of a JDBC {@link Connection}. */
    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    static SchemaSnapshot introspect(
            String dataSourceName, ConnectionSource connectionSource, ScanBudget budget, DatabaseAdvisorLimits limits) {
        if (budget.exhausted()) {
            return SchemaSnapshot.failed(
                    dataSourceName, "The Database Advisor scan budget ran out before this datasource was read.");
        }
        try (Connection connection = connectionSource.get()) {
            if (connection == null) {
                return SchemaSnapshot.failed(dataSourceName, "The DataSource returned no connection.");
            }
            return introspect(dataSourceName, connection, budget, limits);
        } catch (SQLException | RuntimeException ex) {
            return SchemaSnapshot.failed(dataSourceName, describe(ex));
        }
    }

    private static SchemaSnapshot introspect(
            String dataSourceName, Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        Boolean originalReadOnly = currentReadOnly(connection);
        boolean readOnlyApplied = trySetReadOnly(connection);
        try {
            return read(dataSourceName, connection, budget, limits);
        } finally {
            restoreReadOnly(connection, originalReadOnly, readOnlyApplied);
        }
    }

    private static SchemaSnapshot read(
            String dataSourceName, Connection connection, ScanBudget budget, DatabaseAdvisorLimits limits)
            throws SQLException {
        List<SchemaDiagnostic> diagnostics = new ArrayList<>();
        DatabaseMetaData metaData = connection.getMetaData();
        String productName = safeString(metaData::getDatabaseProductName);
        String productVersion = safeString(metaData::getDatabaseProductVersion);
        String url = safeString(metaData::getURL);
        Dialect dialect = Dialect.detect(productName, productVersion, url);
        DatabaseVersion version = readVersion(metaData, productVersion);
        DialectCapabilities capabilities = DialectCapabilities.of(dialect, version);

        TableReadResult tableResult = readTables(dataSourceName, connection, metaData, budget, limits, diagnostics);

        VendorFindings.Builder vendorFindings = VendorFindings.builder();
        if (dialect == Dialect.POSTGRESQL) {
            PostgresCatalogReader.read(connection, version, capabilities, budget, limits, vendorFindings);
        } else if (dialect.isMySqlFamily()) {
            MySqlCatalogReader.read(connection, dialect, capabilities, budget, limits, vendorFindings);
        }
        VendorFindings findings = vendorFindings.build();
        for (VendorAugmentation<?> failure : findings.failures()) {
            diagnostics.add(SchemaDiagnostic.warning(dataSourceName, failure.reason()));
        }
        for (VendorAugmentation<?> truncation : findings.truncations()) {
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    truncation.kind().label() + " was truncated at " + limits.maxVendorFindings()
                            + " rows; some findings may be missing."));
        }

        List<TableModel> tables = VendorSchemaMerge.merge(tableResult.tables(), dialect, findings);
        return new SchemaSnapshot(
                dataSourceName,
                dialect,
                productName,
                version,
                readIdentifierCase(metaData),
                tables,
                findings,
                diagnostics,
                tableResult.truncated(),
                null);
    }

    private record TableReadResult(List<TableModel> tables, boolean truncated) {}

    private static TableReadResult readTables(
            String dataSourceName,
            Connection connection,
            DatabaseMetaData metaData,
            ScanBudget budget,
            DatabaseAdvisorLimits limits,
            List<SchemaDiagnostic> diagnostics)
            throws SQLException {
        String catalog = safeString(connection::getCatalog);
        List<TableRef> refs = readTableRefs(dataSourceName, metaData, catalog, limits, diagnostics);
        boolean truncated = refs.size() > limits.maxTables();
        if (truncated) {
            refs = refs.subList(0, limits.maxTables());
            diagnostics.add(SchemaDiagnostic.warning(
                    dataSourceName,
                    "Only the first " + limits.maxTables()
                            + " tables were analyzed; this schema has more, so some findings may be missing."));
        }
        String escape = searchStringEscape(metaData);
        List<TableModel> tables = new ArrayList<>();
        for (TableRef ref : refs) {
            if (budget.exhausted()) {
                truncated = true;
                diagnostics.add(SchemaDiagnostic.warning(
                        dataSourceName,
                        "The scan budget ran out after " + tables.size()
                                + " tables; the remaining tables were not analyzed."));
                break;
            }
            TableModel table = readTable(metaData, ref, escape, limits);
            tables.add(table);
            for (String issue : table.metadata().issues()) {
                diagnostics.add(SchemaDiagnostic.warning(dataSourceName + "/" + table.qualifiedName(), issue));
            }
        }
        return new TableReadResult(tables, truncated);
    }

    private record TableRef(String catalog, String schema, String name, String type) {}

    private static List<TableRef> readTableRefs(
            String dataSourceName,
            DatabaseMetaData metaData,
            String catalog,
            DatabaseAdvisorLimits limits,
            List<SchemaDiagnostic> diagnostics)
            throws SQLException {
        try {
            return readTableRefs(metaData, catalog, TABLE_TYPES, limits);
        } catch (SQLException ex) {
            // Not every driver accepts a table type it does not know; retry with the universal "TABLE" type
            // rather than losing the whole datasource over PostgreSQL's partitioned-table type.
            diagnostics.add(SchemaDiagnostic.info(
                    dataSourceName,
                    "The driver rejected the PARTITIONED TABLE type filter; retried with TABLE only (" + describe(ex)
                            + ")."));
            return readTableRefs(metaData, catalog, FALLBACK_TABLE_TYPES, limits);
        }
    }

    private static List<TableRef> readTableRefs(
            DatabaseMetaData metaData, String catalog, String[] types, DatabaseAdvisorLimits limits)
            throws SQLException {
        List<TableRef> refs = new ArrayList<>();
        try (ResultSet rs = metaData.getTables(catalog, null, "%", types)) {
            // One row past the bound: seeing max + 1 candidates is what makes truncation observable.
            while (rs.next() && refs.size() <= limits.maxTables()) {
                String tableSchema = rs.getString("TABLE_SCHEM");
                String tableName = rs.getString("TABLE_NAME");
                if (tableName == null || isSystemSchema(tableSchema)) {
                    continue;
                }
                refs.add(new TableRef(rs.getString("TABLE_CAT"), tableSchema, tableName, rs.getString("TABLE_TYPE")));
            }
        }
        return refs;
    }

    private static TableModel readTable(
            DatabaseMetaData metaData, TableRef ref, String escape, DatabaseAdvisorLimits limits) {
        List<String> issues = new ArrayList<>();
        boolean truncated = false;

        List<ColumnModel> columns = List.of();
        boolean columnsRead = true;
        try {
            ColumnReadResult result = readColumns(metaData, ref, escape, limits);
            columns = result.columns();
            truncated |= result.truncated();
            if (result.truncated()) {
                issues.add("Only the first " + limits.maxColumnsPerTable() + " columns of " + qualified(ref)
                        + " were read.");
            }
        } catch (SQLException ex) {
            columnsRead = false;
            issues.add("Columns of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        String primaryKeyName = null;
        List<String> primaryKeyColumns = List.of();
        boolean primaryKeyRead = true;
        try {
            PrimaryKey primaryKey = readPrimaryKey(metaData, ref);
            primaryKeyName = primaryKey.name();
            primaryKeyColumns = primaryKey.columns();
        } catch (SQLException ex) {
            primaryKeyRead = false;
            issues.add("The primary key of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        List<ForeignKeyModel> foreignKeys = List.of();
        boolean foreignKeysRead = true;
        try {
            foreignKeys = readForeignKeys(metaData, ref.catalog(), ref.schema(), ref.name());
        } catch (SQLException ex) {
            foreignKeysRead = false;
            issues.add("Foreign keys of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        List<IndexModel> indexes = List.of();
        boolean indexesRead = true;
        try {
            IndexReadResult result = readIndexes(metaData, ref, limits);
            indexes = result.indexes();
            truncated |= result.truncated();
            if (result.truncated()) {
                issues.add("Only the first " + limits.maxIndexesPerTable() + " indexes of " + qualified(ref)
                        + " were read.");
            }
        } catch (SQLException ex) {
            indexesRead = false;
            issues.add("Indexes of " + qualified(ref) + " could not be read: " + describe(ex));
        }

        TableMetadata metadata =
                new TableMetadata(columnsRead, primaryKeyRead, foreignKeysRead, indexesRead, truncated, issues);
        return new TableModel(
                ref.catalog(),
                ref.schema(),
                ref.name(),
                ref.type(),
                columns,
                primaryKeyName,
                primaryKeyColumns,
                foreignKeys,
                indexes,
                "PARTITIONED TABLE".equalsIgnoreCase(ref.type()),
                false,
                false,
                metadata);
    }

    private record ColumnReadResult(List<ColumnModel> columns, boolean truncated) {}

    private static ColumnReadResult readColumns(
            DatabaseMetaData metaData, TableRef ref, String escape, DatabaseAdvisorLimits limits) throws SQLException {
        List<ColumnModel> columns = new ArrayList<>();
        boolean truncated = false;
        try (ResultSet rs = metaData.getColumns(
                ref.catalog(), escapePattern(ref.schema(), escape), escapePattern(ref.name(), escape), "%")) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                if (tableName != null && !tableName.equalsIgnoreCase(ref.name())) {
                    // getColumns takes patterns, so an escaped-but-still-matching sibling table can appear.
                    continue;
                }
                if (columns.size() >= limits.maxColumnsPerTable()) {
                    truncated = true;
                    break;
                }
                columns.add(readColumn(rs));
            }
        }
        return new ColumnReadResult(columns, truncated);
    }

    private static ColumnModel readColumn(ResultSet rs) throws SQLException {
        Integer size = nullableInt(rs, "COLUMN_SIZE");
        Integer decimalDigits = nullableInt(rs, "DECIMAL_DIGITS");
        return new ColumnModel(
                rs.getString("COLUMN_NAME"),
                rs.getString("TYPE_NAME"),
                rs.getInt("DATA_TYPE"),
                nullability(rs.getInt("NULLABLE")),
                size,
                decimalDigits,
                "YES".equalsIgnoreCase(safeColumn(rs, "IS_AUTOINCREMENT")));
    }

    private record PrimaryKey(String name, List<String> columns) {}

    private static PrimaryKey readPrimaryKey(DatabaseMetaData metaData, TableRef ref) throws SQLException {
        Map<Short, String> byPosition = new LinkedHashMap<>();
        String name = null;
        try (ResultSet rs = metaData.getPrimaryKeys(ref.catalog(), ref.schema(), ref.name())) {
            while (rs.next()) {
                byPosition.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                if (name == null) {
                    name = rs.getString("PK_NAME");
                }
            }
        }
        List<String> columns = byPosition.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
        return new PrimaryKey(name, columns);
    }

    static List<ForeignKeyModel> readForeignKeys(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Map<String, List<String>> columnsByFkName = new LinkedHashMap<>();
        Map<String, List<String>> referencedColumnsByFkName = new LinkedHashMap<>();
        Map<String, TableRef> referencedTableByFkName = new LinkedHashMap<>();
        // JDBC guarantees getImportedKeys() rows are ordered by FKTABLE_CAT/SCHEM/NAME, KEY_SEQ, so all
        // columns belonging to the same constraint are contiguous with an increasing KEY_SEQ starting at
        // 1. Unnamed constraints (FK_NAME null) therefore only need a new synthetic key when KEY_SEQ
        // restarts at 1 — otherwise a composite unnamed foreign key would be split into one fake
        // single-column constraint per row.
        String currentUnnamedKey = null;
        int unnamedCount = 0;
        try (ResultSet rs = metaData.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                String fkName = rs.getString("FK_NAME");
                short keySeq = rs.getShort("KEY_SEQ");
                String key;
                if (fkName != null) {
                    key = fkName;
                } else {
                    if (currentUnnamedKey == null || keySeq <= 1) {
                        key = "fk#" + unnamedCount++;
                        currentUnnamedKey = key;
                    } else {
                        key = currentUnnamedKey;
                    }
                }
                columnsByFkName
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(rs.getString("FKCOLUMN_NAME"));
                referencedColumnsByFkName
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(rs.getString("PKCOLUMN_NAME"));
                referencedTableByFkName.putIfAbsent(
                        key,
                        new TableRef(
                                rs.getString("PKTABLE_CAT"),
                                rs.getString("PKTABLE_SCHEM"),
                                rs.getString("PKTABLE_NAME"),
                                "TABLE"));
            }
        }
        List<ForeignKeyModel> foreignKeys = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : columnsByFkName.entrySet()) {
            TableRef referenced = referencedTableByFkName.get(entry.getKey());
            foreignKeys.add(new ForeignKeyModel(
                    entry.getKey(),
                    entry.getValue(),
                    referenced == null ? null : referenced.catalog(),
                    referenced == null ? null : referenced.schema(),
                    referenced == null ? null : referenced.name(),
                    referencedColumnsByFkName.getOrDefault(entry.getKey(), List.of())));
        }
        return foreignKeys;
    }

    private record IndexReadResult(List<IndexModel> indexes, boolean truncated) {}

    private static IndexReadResult readIndexes(DatabaseMetaData metaData, TableRef ref, DatabaseAdvisorLimits limits)
            throws SQLException {
        Map<String, List<IndexKeyPart>> partsByIndex = new LinkedHashMap<>();
        Map<String, Boolean> uniqueByIndex = new LinkedHashMap<>();
        Map<String, String> filterByIndex = new LinkedHashMap<>();
        Map<String, String> methodByIndex = new LinkedHashMap<>();
        boolean truncated = false;
        // approximate = true keeps this off the table-statistics path some drivers take otherwise; the index
        // definitions themselves are exact either way.
        try (ResultSet rs = metaData.getIndexInfo(ref.catalog(), ref.schema(), ref.name(), false, true)) {
            while (rs.next()) {
                short type = rs.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                if (indexName == null) {
                    continue;
                }
                if (!partsByIndex.containsKey(indexName) && partsByIndex.size() >= limits.maxIndexesPerTable()) {
                    truncated = true;
                    break;
                }
                String columnName = rs.getString("COLUMN_NAME");
                String ascOrDesc = rs.getString("ASC_OR_DESC");
                Boolean ascending = ascOrDesc == null ? null : "A".equalsIgnoreCase(ascOrDesc);
                partsByIndex
                        .computeIfAbsent(indexName, ignored -> new ArrayList<>())
                        .add(
                                columnName == null
                                        ? IndexKeyPart.expression(null)
                                        : IndexKeyPart.column(columnName, ascending));
                uniqueByIndex.putIfAbsent(indexName, !rs.getBoolean("NON_UNIQUE"));
                String filterCondition = rs.getString("FILTER_CONDITION");
                if (filterCondition != null && !filterCondition.isBlank()) {
                    filterByIndex.putIfAbsent(indexName, filterCondition);
                }
                methodByIndex.putIfAbsent(indexName, indexMethod(type));
            }
        }
        List<IndexModel> indexes = new ArrayList<>();
        for (Map.Entry<String, List<IndexKeyPart>> entry : partsByIndex.entrySet()) {
            indexes.add(new IndexModel(
                    entry.getKey(),
                    entry.getValue(),
                    uniqueByIndex.getOrDefault(entry.getKey(), false),
                    methodByIndex.get(entry.getKey()),
                    filterByIndex.get(entry.getKey()),
                    IndexModel.Visibility.UNKNOWN,
                    IndexModel.Validity.UNKNOWN));
        }
        return new IndexReadResult(indexes, truncated);
    }

    private static String indexMethod(short type) {
        return switch (type) {
            case DatabaseMetaData.tableIndexClustered -> "clustered";
            case DatabaseMetaData.tableIndexHashed -> "hashed";
            case DatabaseMetaData.tableIndexOther -> null;
            default -> null;
        };
    }

    private static ColumnModel.Nullability nullability(int reported) {
        return switch (reported) {
            case DatabaseMetaData.columnNullable -> ColumnModel.Nullability.NULLABLE;
            case DatabaseMetaData.columnNoNulls -> ColumnModel.Nullability.NOT_NULL;
            default -> ColumnModel.Nullability.UNKNOWN;
        };
    }

    private static DatabaseVersion readVersion(DatabaseMetaData metaData, String productVersion) {
        try {
            return DatabaseVersion.of(
                    metaData.getDatabaseMajorVersion(), metaData.getDatabaseMinorVersion(), productVersion);
        } catch (SQLException | RuntimeException ex) {
            return DatabaseVersion.UNKNOWN;
        }
    }

    private static String readIdentifierCase(DatabaseMetaData metaData) {
        try {
            if (metaData.storesUpperCaseIdentifiers()) {
                return "UPPER";
            }
            if (metaData.storesLowerCaseIdentifiers()) {
                return "LOWER";
            }
            if (metaData.supportsMixedCaseIdentifiers() || metaData.storesMixedCaseIdentifiers()) {
                return "MIXED";
            }
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
        return null;
    }

    private static String searchStringEscape(DatabaseMetaData metaData) {
        try {
            return metaData.getSearchStringEscape();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    /** Escapes JDBC metadata pattern wildcards so a table named {@code user_data} matches only itself. */
    private static String escapePattern(String value, String escape) {
        if (value == null || escape == null || escape.isEmpty()) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '_'
                    || character == '%'
                    || String.valueOf(character).equals(escape)) {
                escaped.append(escape);
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    private static boolean isSystemSchema(String schema) {
        if (schema == null) {
            return false;
        }
        String normalized = schema.toLowerCase(Locale.ROOT);
        return SYSTEM_SCHEMAS.contains(normalized)
                || normalized.startsWith("pg_temp")
                || normalized.startsWith("pg_toast");
    }

    private static Boolean currentReadOnly(Connection connection) {
        try {
            return connection.isReadOnly();
        } catch (SQLException ex) {
            return null;
        }
    }

    private static boolean trySetReadOnly(Connection connection) {
        try {
            connection.setReadOnly(true);
            return true;
        } catch (SQLException ex) {
            // Not every driver supports read-only mode; the scanner never issues a write regardless.
            return false;
        }
    }

    /**
     * Puts the connection back exactly as it was found. Pooled connections are reused by the application, so
     * leaving one flipped to read-only would break the next writer that borrows it.
     */
    private static void restoreReadOnly(Connection connection, Boolean originalReadOnly, boolean applied) {
        if (!applied || originalReadOnly == null || originalReadOnly) {
            return;
        }
        try {
            connection.setReadOnly(false);
        } catch (SQLException ex) {
            // The connection is about to be closed/returned; a driver that refuses the restore is not
            // actionable here and must not mask the schema results.
        }
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String safeColumn(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface MetaDataString {
        String get() throws SQLException;
    }

    private static String safeString(MetaDataString supplier) {
        try {
            return supplier.get();
        } catch (SQLException | RuntimeException ex) {
            return null;
        }
    }

    private static String qualified(TableRef ref) {
        return ref.schema() == null || ref.schema().isBlank() ? ref.name() : ref.schema() + "." + ref.name();
    }

    private static String describe(Exception ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        return CredentialRedaction.redact(message);
    }
}
