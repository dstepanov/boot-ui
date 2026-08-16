package io.github.jdubois.bootui.engine.databaseadvisor;

/** One MySQL/MariaDB character column's declared character set and collation. */
record MySqlColumnCharset(String schema, String table, String column, String characterSet, String collation) {

    String qualifiedColumn() {
        String qualifiedTable = schema == null || schema.isBlank() ? table : schema + "." + table;
        return qualifiedTable + "." + column;
    }
}
