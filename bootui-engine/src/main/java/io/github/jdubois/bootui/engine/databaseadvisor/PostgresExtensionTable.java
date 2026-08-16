package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * A PostgreSQL table owned by an installed extension ({@code pg_depend.deptype = 'e'}), such as
 * {@code pg_stat_statements}' or PostGIS' bookkeeping tables. Users do not control these definitions, so
 * schema-hygiene findings against them are noise.
 */
record PostgresExtensionTable(String schema, String table, String extension) {}
