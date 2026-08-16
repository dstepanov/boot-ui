package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Oracle-specific: an index the catalog reports unusable — {@code all_indexes.status = 'UNUSABLE'} for an
 * ordinary index, or an individual partition/subpartition reported {@code UNUSABLE} for a partitioned one,
 * where the index's own {@code status} reads {@code N/A} instead. An unusable index is silently skipped by
 * the optimizer, and (unless {@code SKIP_UNUSABLE_INDEXES} is enabled, which is Oracle's default since 10g)
 * every {@code INSERT}/{@code UPDATE} against the underlying table can fail outright until it is rebuilt.
 *
 * <p>Domain indexes ({@code index_type = 'DOMAIN'}, e.g. Oracle Text or Spatial) are excluded: their status
 * semantics are governed by the domain index implementation's own auxiliary objects, which {@code
 * all_indexes.status} alone cannot reliably describe, and a wrong "this is broken" here would be worse than
 * staying quiet. Every other index type — normal, function-based, bitmap, LOB, IOT-backing — uses the
 * standard {@code status} semantics and is reported the same way, including one Oracle created automatically
 * to back a primary key or unique constraint: an unusable constraint-backing index is, if anything, more
 * consequential to miss.</p>
 */
final class OracleUnusableIndexRule extends AbstractDatabaseAdvisorRule {

    OracleUnusableIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-ORACLE-001",
                "Unusable Oracle indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.HIGH,
                "Detects Oracle indexes reported UNUSABLE by all_indexes.status, and — for a partitioned "
                        + "index, whose own status reads N/A — individual UNUSABLE partitions/subpartitions in "
                        + "all_ind_partitions/all_ind_subpartitions. Domain indexes are excluded: their status "
                        + "semantics need their own domain index implementation to interpret reliably.",
                "Rebuild the index (ALTER INDEX ... REBUILD, or ALTER INDEX ... REBUILD PARTITION/SUBPARTITION "
                        + "for a single partition) during a maintenance window. An unusable index is never used "
                        + "by the optimizer, and unless SKIP_UNUSABLE_INDEXES is enabled, DML against the "
                        + "underlying table can fail outright until it is fixed.",
                "https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/ALTER-INDEX.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<SchemaSnapshot> schemas = context.schemasOf(Dialect.ORACLE);
        String skipReason = VendorRuleSupport.skipReason(
                schemas, VendorFindingKinds.ORACLE_INDEX_DETAILS, "No Oracle datasource was detected.");
        if (skipReason != null) {
            return skipped(skipReason);
        }
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : schemas) {
            if (VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
                checkOrdinaryIndexes(schema, details);
            }
            if (VendorRuleSupport.available(schema, VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
                checkPartitions(schema, details);
            }
        }
        return violation(details);
    }

    private void checkOrdinaryIndexes(SchemaSnapshot schema, List<String> details) {
        for (OracleIndexDetail index : schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_DETAILS)) {
            if (index.partitioned() || index.domain() || index.usable()) {
                continue;
            }
            String flavor = index.automatic() ? " (automatically created to back a constraint)" : "";
            details.add(schema.dataSourceName() + ": index " + index.index() + flavor + " on table "
                    + index.qualifiedTable() + " is UNUSABLE (" + index.indexType() + ").");
        }
    }

    private void checkPartitions(SchemaSnapshot schema, List<String> details) {
        for (OracleIndexPartitionStatus partition :
                schema.vendorFindings().findings(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS)) {
            if (!partition.unusable()) {
                continue;
            }
            String level = partition.subpartition() ? "subpartition" : "partition";
            details.add(schema.dataSourceName() + ": " + level + " " + partition.partitionName() + " of index "
                    + partition.index() + " on table " + partition.qualifiedTable() + " is UNUSABLE.");
        }
    }
}
