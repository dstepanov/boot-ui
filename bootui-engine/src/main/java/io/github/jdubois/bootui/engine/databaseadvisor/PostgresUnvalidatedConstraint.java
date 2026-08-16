package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL foreign-key or check constraint added with {@code NOT VALID} and never validated
 * ({@code pg_constraint.convalidated = false}): it is enforced for new rows only, so existing rows may
 * already violate it and the planner cannot rely on it.
 *
 * @param type {@code f} for a foreign key, {@code c} for a check constraint
 */
record PostgresUnvalidatedConstraint(String schema, String table, String constraint, String type, String definition) {

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    String describeType() {
        return "f".equals(type) ? "foreign key" : "check";
    }
}
