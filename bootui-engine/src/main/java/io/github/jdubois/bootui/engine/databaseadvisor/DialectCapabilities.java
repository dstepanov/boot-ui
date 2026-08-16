package io.github.jdubois.bootui.engine.databaseadvisor;

/**
 * What one datasource's catalog can answer, derived from its {@link Dialect} and {@link DatabaseVersion}.
 *
 * <p>Every capability gates a catalog column or view that simply does not exist on older servers, so the
 * introspector can select the right SQL up front instead of discovering the gap through a failed query —
 * and can report an honest "not supported by this server version" reason when a rule's data is missing,
 * rather than silently reporting no findings.</p>
 */
record DialectCapabilities(
        boolean indexVisibility, boolean indexExpression, boolean sequencesView, boolean declarativePartitioning) {

    static final DialectCapabilities NONE = new DialectCapabilities(false, false, false, false);

    static DialectCapabilities of(Dialect dialect, DatabaseVersion version) {
        return switch (dialect) {
            case POSTGRESQL -> new DialectCapabilities(false, true, version.atLeast(10, 0), version.atLeast(10, 0));
            // MySQL 8.0 added invisible indexes (information_schema.statistics.IS_VISIBLE); 8.0.13 added
            // functional key parts and the EXPRESSION column that describes them.
            case MYSQL -> new DialectCapabilities(version.atLeast(8, 0), version.atLeast(8, 0, 13), false, false);
            // MariaDB 10.6 added "ignored" indexes (information_schema.statistics.IGNORED). MariaDB has no
            // EXPRESSION column in information_schema.statistics.
            case MARIADB -> new DialectCapabilities(version.atLeast(10, 6), false, false, false);
            case GENERIC -> NONE;
        };
    }
}
