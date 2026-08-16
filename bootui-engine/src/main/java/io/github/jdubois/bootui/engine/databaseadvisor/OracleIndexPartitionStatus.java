package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * One unusable partition or subpartition of a partitioned Oracle index ({@code all_ind_partitions}/
 * {@code all_ind_subpartitions}). A partitioned index's own {@code all_indexes.status} reads {@code N/A}, so
 * this is the only place an unusable partition is visible at all — usually left behind by a partition
 * maintenance operation (a split, exchange, or truncate) that did not rebuild every index partition.
 */
record OracleIndexPartitionStatus(
        String schema, String table, String index, String partitionName, boolean subpartition, String status) {

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    boolean unusable() {
        return "UNUSABLE".equalsIgnoreCase(status);
    }
}
