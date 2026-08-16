package io.github.jdubois.bootui.engine.databaseadvisor;

import java.math.BigInteger;
import java.util.Locale;

/**
 * One MySQL/MariaDB {@code AUTO_INCREMENT} column, with the signedness-aware capacity of its integer type —
 * the number the table's {@code AUTO_INCREMENT} counter must stay below or every subsequent insert fails with
 * "Duplicate entry ... for key PRIMARY".
 */
record MySqlAutoIncrementColumn(String schema, String table, String column, String dataType, String columnType) {

    private static final BigInteger TINYINT = BigInteger.valueOf(127);
    private static final BigInteger TINYINT_UNSIGNED = BigInteger.valueOf(255);
    private static final BigInteger SMALLINT = BigInteger.valueOf(32767);
    private static final BigInteger SMALLINT_UNSIGNED = BigInteger.valueOf(65535);
    private static final BigInteger MEDIUMINT = BigInteger.valueOf(8388607);
    private static final BigInteger MEDIUMINT_UNSIGNED = BigInteger.valueOf(16777215);
    private static final BigInteger INT = BigInteger.valueOf(2147483647L);
    private static final BigInteger INT_UNSIGNED = BigInteger.valueOf(4294967295L);
    private static final BigInteger BIGINT = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger BIGINT_UNSIGNED = new BigInteger("18446744073709551615");

    String qualifiedTable() {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    boolean unsigned() {
        return columnType != null && columnType.toLowerCase(Locale.ROOT).contains("unsigned");
    }

    /** The largest value this column can hold, or {@code null} for a type this rule does not classify. */
    BigInteger capacity() {
        String type = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        boolean unsigned = unsigned();
        return switch (type) {
            case "tinyint" -> unsigned ? TINYINT_UNSIGNED : TINYINT;
            case "smallint" -> unsigned ? SMALLINT_UNSIGNED : SMALLINT;
            case "mediumint" -> unsigned ? MEDIUMINT_UNSIGNED : MEDIUMINT;
            case "int", "integer" -> unsigned ? INT_UNSIGNED : INT;
            case "bigint" -> unsigned ? BIGINT_UNSIGNED : BIGINT;
            default -> null;
        };
    }
}
