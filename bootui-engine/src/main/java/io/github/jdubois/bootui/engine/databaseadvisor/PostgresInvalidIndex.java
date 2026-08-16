package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL index whose {@code pg_index} catalog entry reports it as unusable: {@code indisvalid = false}
 * (typically a failed {@code CREATE INDEX CONCURRENTLY}), {@code indisready = false} (not yet accepting
 * inserts), or {@code indislive = false} (being dropped concurrently).
 *
 * @param schema the index's table schema, always qualified
 * @param table the indexed table
 * @param index the index name
 * @param valid {@code pg_index.indisvalid}
 * @param ready {@code pg_index.indisready}
 * @param live {@code pg_index.indislive}
 */
record PostgresInvalidIndex(String schema, String table, String index, boolean valid, boolean ready, boolean live) {

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    String describeFlags() {
        StringBuilder flags = new StringBuilder();
        if (!valid) {
            flags.append("indisvalid=false");
        }
        if (!ready) {
            flags.append(flags.isEmpty() ? "" : ", ").append("indisready=false");
        }
        if (!live) {
            flags.append(flags.isEmpty() ? "" : ", ").append("indislive=false");
        }
        return flags.toString();
    }
}
