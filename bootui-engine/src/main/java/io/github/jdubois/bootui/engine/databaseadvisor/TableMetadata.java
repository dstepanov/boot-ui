package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.List;

/**
 * How completely one table's metadata could be read, so a rule can tell "this table genuinely has no
 * primary key" apart from "the driver refused to answer {@code getPrimaryKeys()} for this table".
 *
 * <p>A rule that needs a part which could not be read must skip that table (and the scan reports the
 * failure as a diagnostic) instead of counting the gap as a clean result.</p>
 *
 * @param columnsRead whether {@code getColumns} succeeded
 * @param primaryKeyRead whether {@code getPrimaryKeys} succeeded
 * @param foreignKeysRead whether {@code getImportedKeys} succeeded
 * @param indexesRead whether {@code getIndexInfo} succeeded
 * @param truncated whether a per-table bound (columns or indexes) cut the metadata short
 * @param issues human-readable, already-sanitized reasons for every gap above
 */
record TableMetadata(
        boolean columnsRead,
        boolean primaryKeyRead,
        boolean foreignKeysRead,
        boolean indexesRead,
        boolean truncated,
        List<String> issues) {

    static final TableMetadata COMPLETE = new TableMetadata(true, true, true, true, false, List.of());

    TableMetadata {
        issues = List.copyOf(issues);
    }

    boolean complete() {
        return columnsRead && primaryKeyRead && foreignKeysRead && indexesRead && !truncated;
    }
}
