package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * Maps a {@code GENERATED ... AS IDENTITY} column ({@code all_tab_identity_cols}) to the internally-named
 * sequence Oracle created to back it (typically {@code ISEQ$$_<object id>}), purely so a finding about that
 * sequence can name the column it actually serves instead of an opaque system-generated sequence name.
 */
record OracleIdentityColumn(String schema, String table, String column, String sequenceName) {

    String qualifiedColumn() {
        String qualifiedTable = schema == null || schema.isBlank() ? table : schema + "." + table;
        return qualifiedTable + "." + column;
    }
}
