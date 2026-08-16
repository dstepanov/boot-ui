package io.github.jdubois.bootui.engine.databaseadvisor;

import java.sql.Types;
import java.util.Locale;
import java.util.Map;

/**
 * Compares a foreign-key column against the column it actually references, beyond a coarse type family: an
 * {@code INT} child referencing a {@code BIGINT} parent has the same family but a narrower range, which is
 * exactly the mismatch that silently breaks once the parent's ids grow past 2^31.
 *
 * <p>Every comparison is conservative. A type this class cannot classify — a domain, an enum, a vendor type,
 * an unknown width — produces no finding at all rather than a guess, because the cost of a wrong "your
 * foreign key is broken" is far higher than the cost of a missed one.</p>
 */
final class ColumnTypeCompatibility {

    /** Integer rank so a narrower child column can be told from a merely differently-named one. */
    private static final Map<String, Integer> INTEGER_RANKS = Map.of(
            "tinyint", 1,
            "smallint", 2,
            "int2", 2,
            "mediumint", 3,
            "int", 4,
            "integer", 4,
            "int4", 4,
            "bigint", 5,
            "int8", 5);

    private ColumnTypeCompatibility() {}

    /**
     * Describes how {@code child} disagrees with {@code parent}, or {@code null} when they are compatible or
     * cannot be compared confidently.
     */
    static String mismatch(ColumnModel child, ColumnModel parent) {
        if (child == null || parent == null) {
            return null;
        }
        JdbcTypeFamily childFamily = JdbcTypeFamily.of(child);
        JdbcTypeFamily parentFamily = JdbcTypeFamily.of(parent);
        if (childFamily == JdbcTypeFamily.OTHER || parentFamily == JdbcTypeFamily.OTHER) {
            return null;
        }
        if (childFamily != parentFamily) {
            return "a type-family mismatch (" + childFamily.name().toLowerCase(Locale.ROOT) + " vs "
                    + parentFamily.name().toLowerCase(Locale.ROOT) + ")";
        }
        return switch (childFamily) {
            case NUMERIC -> numericMismatch(child, parent);
            case STRING, BINARY -> widthMismatch(child, parent);
            default -> null;
        };
    }

    private static String numericMismatch(ColumnModel child, ColumnModel parent) {
        Integer childRank = integerRank(child);
        Integer parentRank = integerRank(parent);
        if (childRank != null && parentRank != null) {
            if (childRank < parentRank) {
                return "a narrower integer type that cannot hold every referenced value";
            }
            if (child.unsigned() != parent.unsigned()) {
                return "a signedness mismatch (" + (child.unsigned() ? "unsigned" : "signed") + " vs "
                        + (parent.unsigned() ? "unsigned" : "signed") + ")";
            }
            return null;
        }
        if (isDecimal(child) && isDecimal(parent)) {
            return decimalMismatch(child, parent);
        }
        return null;
    }

    private static String decimalMismatch(ColumnModel child, ColumnModel parent) {
        if (child.size() == null || parent.size() == null) {
            return null;
        }
        int childScale = child.decimalDigits() == null ? 0 : child.decimalDigits();
        int parentScale = parent.decimalDigits() == null ? 0 : parent.decimalDigits();
        if (childScale != parentScale) {
            return "a different numeric scale (" + childScale + " vs " + parentScale + ")";
        }
        if (child.size() < parent.size()) {
            return "a smaller numeric precision (" + child.size() + " vs " + parent.size() + ")";
        }
        return null;
    }

    private static String widthMismatch(ColumnModel child, ColumnModel parent) {
        if (child.size() == null || parent.size() == null || child.size() <= 0 || parent.size() <= 0) {
            return null;
        }
        if (child.size() < parent.size()) {
            return "a shorter declared length (" + child.size() + " vs " + parent.size() + ")";
        }
        return null;
    }

    private static boolean isDecimal(ColumnModel column) {
        return column.jdbcType() == Types.DECIMAL || column.jdbcType() == Types.NUMERIC;
    }

    private static Integer integerRank(ColumnModel column) {
        if (column.typeName() == null) {
            return rankFromJdbcType(column.jdbcType());
        }
        String normalized = column.typeName().toLowerCase(Locale.ROOT).trim();
        int parenthesis = normalized.indexOf('(');
        if (parenthesis >= 0) {
            normalized = normalized.substring(0, parenthesis).trim();
        }
        normalized =
                normalized.replace(" unsigned", "").replace(" zerofill", "").trim();
        Integer rank = INTEGER_RANKS.get(normalized);
        return rank != null ? rank : rankFromJdbcType(column.jdbcType());
    }

    private static Integer rankFromJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> 1;
            case Types.SMALLINT -> 2;
            case Types.INTEGER -> 4;
            case Types.BIGINT -> 5;
            default -> null;
        };
    }
}
