package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * The {@code pg_index}/{@code pg_am} facts JDBC's {@code getIndexInfo} cannot report on PostgreSQL: whether
 * the index is valid, whether it is partial ({@code indpred}), whether it has expression key parts
 * ({@code indexprs}), and which access method backs it. Merged onto {@link IndexModel} so the index rules can
 * tell a usable index from one that only looks usable.
 */
record PostgresIndexDetail(
        String schema,
        String table,
        String index,
        boolean valid,
        boolean partial,
        String predicate,
        boolean expression,
        String method) {}
