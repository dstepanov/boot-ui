package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Folds the vendor catalog augmentation back onto the generic JDBC schema model: index semantics that
 * {@code getIndexInfo} cannot express (validity, partial predicates, expression and prefix key parts, access
 * method, visibility) and table placement (PostgreSQL partition parents/children and extension-owned tables).
 *
 * <p>Doing this once, here, is what lets every rule work against one enriched model instead of re-deriving
 * vendor semantics: {@code DB-SCHEMA-002} can ask "is there a <em>usable</em> index for these columns?"
 * without knowing that MySQL prefix indexes exist or that PostgreSQL indexes can be invalid.</p>
 */
final class VendorSchemaMerge {

    private VendorSchemaMerge() {}

    static List<TableModel> merge(List<TableModel> tables, Dialect dialect, VendorFindings findings) {
        if (tables.isEmpty()) {
            return tables;
        }
        if (dialect == Dialect.POSTGRESQL) {
            return mergePostgres(tables, findings);
        }
        if (dialect.isMySqlFamily()) {
            return mergeMySql(tables, findings);
        }
        return tables;
    }

    private static List<TableModel> mergePostgres(List<TableModel> tables, VendorFindings findings) {
        Map<String, PostgresIndexDetail> indexDetails = new HashMap<>();
        for (PostgresIndexDetail detail : findings.findings(VendorFindingKinds.POSTGRES_INDEX_DETAILS)) {
            indexDetails.put(key(detail.schema(), detail.table(), detail.index()), detail);
        }
        Map<String, PostgresPartitionInfo> partitions = new HashMap<>();
        for (PostgresPartitionInfo partition : findings.findings(VendorFindingKinds.POSTGRES_PARTITIONS)) {
            partitions.put(key(partition.schema(), partition.table()), partition);
        }
        Set<String> extensionTables = new HashSet<>();
        for (PostgresExtensionTable table : findings.findings(VendorFindingKinds.POSTGRES_EXTENSION_TABLES)) {
            extensionTables.add(key(table.schema(), table.table()));
        }

        List<TableModel> merged = new ArrayList<>();
        for (TableModel table : tables) {
            List<IndexModel> indexes = new ArrayList<>();
            for (IndexModel index : table.indexes()) {
                PostgresIndexDetail detail = indexDetails.get(key(table.schema(), table.name(), index.name()));
                indexes.add(detail == null ? index : enrich(index, detail));
            }
            PostgresPartitionInfo partition = partitions.get(key(table.schema(), table.name()));
            boolean parent = table.partitionParent() || (partition != null && partition.partitionedParent());
            boolean child = partition != null && partition.partitionChild();
            merged.add(table.withIndexes(indexes)
                    .withPlacement(parent, child, extensionTables.contains(key(table.schema(), table.name()))));
        }
        return List.copyOf(merged);
    }

    private static IndexModel enrich(IndexModel index, PostgresIndexDetail detail) {
        List<IndexKeyPart> keyParts = index.keyParts();
        if (detail.expression() && keyParts.stream().noneMatch(IndexKeyPart::isExpression)) {
            // pgjdbc reports the expression's rendered text in COLUMN_NAME, which is indistinguishable from a
            // real column name; pg_index.indexprs is the only reliable signal that a key part is an
            // expression, and an expression index cannot answer a plain column lookup.
            keyParts = List.of(IndexKeyPart.expression(null));
        }
        return new IndexModel(
                index.name(),
                keyParts,
                index.unique(),
                detail.method() == null ? index.method() : detail.method(),
                detail.partial()
                        ? (detail.predicate() == null ? "partial index" : detail.predicate())
                        : index.filterCondition(),
                index.visibility(),
                detail.valid() ? IndexModel.Validity.VALID : IndexModel.Validity.INVALID);
    }

    private static List<TableModel> mergeMySql(List<TableModel> tables, VendorFindings findings) {
        if (!findings.available(VendorFindingKinds.MYSQL_INDEX_DETAILS)) {
            return tables;
        }
        Map<String, List<MySqlIndexDetail>> detailsByIndex = new HashMap<>();
        for (MySqlIndexDetail detail : findings.findings(VendorFindingKinds.MYSQL_INDEX_DETAILS)) {
            detailsByIndex
                    .computeIfAbsent(key(detail.schema(), detail.table(), detail.index()), ignored -> new ArrayList<>())
                    .add(detail);
        }
        List<TableModel> merged = new ArrayList<>();
        for (TableModel table : tables) {
            List<IndexModel> indexes = new ArrayList<>();
            for (IndexModel index : table.indexes()) {
                List<MySqlIndexDetail> details = detailsByIndex.get(indexKey(table, index));
                indexes.add(details == null || details.isEmpty() ? index : enrich(index, details));
            }
            merged.add(table.withIndexes(indexes));
        }
        return List.copyOf(merged);
    }

    /** MySQL reports the schema in {@code TABLE_CAT}; the JDBC {@code TABLE_SCHEM} is null there. */
    private static String indexKey(TableModel table, IndexModel index) {
        String schema = table.schema() == null || table.schema().isBlank() ? table.catalog() : table.schema();
        return key(schema, table.name(), index.name());
    }

    private static IndexModel enrich(IndexModel index, List<MySqlIndexDetail> details) {
        List<MySqlIndexDetail> ordered = new ArrayList<>(details);
        ordered.sort((left, right) -> Integer.compare(left.position(), right.position()));
        List<IndexKeyPart> keyParts = new ArrayList<>();
        Boolean visible = null;
        String indexType = null;
        for (MySqlIndexDetail detail : ordered) {
            if (visible == null) {
                visible = detail.visible();
            }
            if (indexType == null) {
                indexType = detail.indexType();
            }
            if (detail.column() == null) {
                keyParts.add(IndexKeyPart.expression(detail.expression()));
                continue;
            }
            Boolean ascending = detail.collation() == null ? null : "A".equalsIgnoreCase(detail.collation());
            keyParts.add(new IndexKeyPart(detail.column(), null, ascending, detail.subPart(), null));
        }
        if (keyParts.isEmpty()) {
            keyParts = index.keyParts();
        }
        IndexModel.Visibility visibility = visible == null
                ? IndexModel.Visibility.UNKNOWN
                : (visible ? IndexModel.Visibility.VISIBLE : IndexModel.Visibility.INVISIBLE);
        return new IndexModel(
                index.name(),
                keyParts,
                index.unique(),
                indexType == null ? index.method() : indexType.toLowerCase(Locale.ROOT),
                index.filterCondition(),
                visibility,
                index.validity());
    }

    private static String key(String schema, String table) {
        return normalize(schema) + "." + normalize(table);
    }

    private static String key(String schema, String table, String index) {
        return key(schema, table) + "." + normalize(index);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
