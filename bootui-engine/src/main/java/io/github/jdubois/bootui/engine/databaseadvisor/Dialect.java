package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.Locale;

/**
 * The JDBC dialect family detected for one datasource, used to decide which read-only catalog
 * augmentation queries (if any) the {@link SchemaIntrospector} may run in addition to the generic
 * {@code java.sql.DatabaseMetaData} introspection, and which vendor rules apply. Every other dialect
 * (H2, SQL Server, Oracle, ...) still gets a full schema scan through {@link #GENERIC}; it is never a
 * reason to fail closed.
 *
 * <p>{@link #MARIADB} is detected explicitly rather than being folded into {@link #MYSQL}: the two
 * share {@code information_schema} table/column/statistics shapes but differ in catalog columns
 * ({@code IS_VISIBLE} on MySQL 8.0 versus {@code IGNORED} on MariaDB 10.6) and in which storage engines
 * are idiomatic, so the vendor rules must be able to tell them apart.</p>
 */
enum Dialect {
    POSTGRESQL("PostgreSQL"),
    MYSQL("MySQL"),
    MARIADB("MariaDB"),
    GENERIC("Generic JDBC");

    private final String label;

    Dialect(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }

    /** True for MySQL and MariaDB, which share the {@code information_schema} catalog augmentation. */
    boolean isMySqlFamily() {
        return this == MYSQL || this == MARIADB;
    }

    /**
     * Detects the dialect from the driver-reported product name, product version, and JDBC URL.
     *
     * <p>MariaDB is checked first and from all three signals because a MariaDB server reached through the
     * MySQL Connector/J driver reports {@code getDatabaseProductName() == "MySQL"} and only discloses its
     * true identity in the version string (for example {@code "10.11.6-MariaDB"}).</p>
     */
    static Dialect detect(String productName, String productVersion, String jdbcUrl) {
        String product = normalize(productName);
        String version = normalize(productVersion);
        String url = normalize(jdbcUrl);
        if (product.contains("mariadb") || version.contains("mariadb") || url.startsWith("jdbc:mariadb")) {
            return MARIADB;
        }
        if (product.contains("postgresql") || url.startsWith("jdbc:postgresql")) {
            return POSTGRESQL;
        }
        if (product.contains("mysql") || url.startsWith("jdbc:mysql")) {
            return MYSQL;
        }
        return GENERIC;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
