package io.github.jdubois.bootui.engine.databaseadvisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link VendorSchemaMerge} folds vendor catalog augmentation onto the generic JDBC model; these tests target
 * the merge logic directly rather than through a rule, since a wrong merge would silently corrupt every rule
 * built on top of it.
 */
class VendorSchemaMergeTests {

    @Test
    void mergePostgresTrimsPgjdbcsMisreportedIncludeColumnsFromTheIndexKey() {
        // pgjdbc's getIndexInfo() reports a covering index's INCLUDE (non-key) column as an ordinary trailing
        // key part (confirmed pgjdbc issue #3430): a unique index declared "ON t (a) INCLUDE (b)" is read back
        // with two key parts, when only "a" is a genuine key column.
        IndexModel misreported = IndexModel.of("uq_a_include_b", List.of("a", "b"), true);
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(
                        DatabaseAdvisorFixtures.column("a", "int8", java.sql.Types.BIGINT),
                        DatabaseAdvisorFixtures.column("b", "int8", java.sql.Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(misreported));
        PostgresIndexDetail detail =
                new PostgresIndexDetail("public", "t", "uq_a_include_b", true, false, null, false, "btree", 1, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings);

        IndexModel mergedIndex = merged.get(0).indexes().get(0);
        assertThat(mergedIndex.keyParts()).hasSize(1);
        assertThat(mergedIndex.columnNames()).containsExactly("a");
        // The truncated index now genuinely enforces uniqueness over (a) alone, matching what INCLUDE means.
        assertThat(mergedIndex.enforcesUniquenessOver(List.of("a"))).isTrue();
    }

    @Test
    void mergePostgresLeavesAnOrdinaryIndexesKeyPartsUntouchedWhenKeyColumnCountMatches() {
        IndexModel plain = IndexModel.of("ix_plain", List.of("a", "b"), false);
        TableModel table = TableModel.of(
                "app",
                "public",
                "t",
                List.of(
                        DatabaseAdvisorFixtures.column("a", "int8", java.sql.Types.BIGINT),
                        DatabaseAdvisorFixtures.column("b", "int8", java.sql.Types.BIGINT)),
                List.of(),
                List.of(),
                List.of(plain));
        PostgresIndexDetail detail =
                new PostgresIndexDetail("public", "t", "ix_plain", true, false, null, false, "btree", 2, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.POSTGRES_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.POSTGRESQL, findings);

        assertThat(merged.get(0).indexes().get(0).keyParts()).hasSize(2);
    }

    @Test
    void mergeOracleMarksAConstraintBackedIndexAutomaticAndAnUnusableOneInvalid() {
        IndexModel index = IndexModel.of("SYS_C007", List.of("ID"), true);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "ORDERS", "SYS_C007", "NORMAL", true, "UNUSABLE", "VISIBLE", true, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        IndexModel mergedIndex = merged.get(0).indexes().get(0);
        assertThat(mergedIndex.automatic()).isTrue();
        assertThat(mergedIndex.invalid()).isTrue();
    }

    @Test
    void mergeOracleUsesPartitionStatusInsteadOfTheIndexsOwnNaStatus() {
        IndexModel index = IndexModel.of("IX_PARTITIONED", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "EVENTS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "EVENTS", "IX_PARTITIONED", "NORMAL", false, "N/A", "VISIBLE", false, true);
        OracleIndexPartitionStatus unusablePartition =
                new OracleIndexPartitionStatus("APP", "EVENTS", "IX_PARTITIONED", "P_2024_01", false, "UNUSABLE");
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(
                        VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(unusablePartition), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).invalid()).isTrue();
    }

    @Test
    void mergeOracleTreatsAPartitionedIndexWithNoUnusablePartitionAsValid() {
        IndexModel index = IndexModel.of("IX_PARTITIONED", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "EVENTS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "EVENTS", "IX_PARTITIONED", "NORMAL", false, "N/A", "VISIBLE", false, true);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_PARTITION_STATUS, List.of(), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).invalid()).isFalse();
    }

    @Test
    void mergeOracleMarksANonNormalIndexTypeAsSpecialized() {
        IndexModel index = IndexModel.of("IX_BITMAP", List.of("STATUS"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("STATUS", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail =
                new OracleIndexDetail("APP", "ORDERS", "IX_BITMAP", "BITMAP", false, "VALID", "VISIBLE", false, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        assertThat(merged.get(0).indexes().get(0).specialized()).isTrue();
    }

    @Test
    void mergeOracleUsesExactCaseWhenMatchingIndexNamesAcrossReads() {
        // Oracle is case-sensitive for genuinely distinct quoted identifiers, unlike PostgreSQL/MySQL, so an
        // index named differently only by case must not be merged as if it were the same object.
        IndexModel index = IndexModel.of("MixedCaseIndex", List.of("ID"), false);
        TableModel table = TableModel.of(
                "APP",
                "APP",
                "ORDERS",
                List.of(DatabaseAdvisorFixtures.column("ID", "number", java.sql.Types.NUMERIC)),
                List.of(),
                List.of(),
                List.of(index));
        OracleIndexDetail detail = new OracleIndexDetail(
                "APP", "ORDERS", "mixedcaseindex", "NORMAL", false, "UNUSABLE", "VISIBLE", false, false);
        VendorFindings findings = VendorFindings.builder()
                .add(VendorAugmentation.available(VendorFindingKinds.ORACLE_INDEX_DETAILS, List.of(detail), false))
                .build();

        List<TableModel> merged = VendorSchemaMerge.merge(List.of(table), Dialect.ORACLE, findings);

        // No match by exact case, so the index is left as read generically (unenriched, unknown validity).
        assertThat(merged.get(0).indexes().get(0).invalid()).isFalse();
    }
}
