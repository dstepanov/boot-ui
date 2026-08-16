package io.github.jdubois.bootui.engine.databaseadvisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One physical index read from {@code DatabaseMetaData.getIndexInfo}, enriched (where the vendor catalog
 * can answer) with validity, partial-index predicate, expression key parts, access method and visibility.
 *
 * <p>The extra semantics exist so the rules can answer "does this index actually support that lookup /
 * enforce that uniqueness?" instead of comparing bare column-name lists: an invalid, invisible, partial,
 * expression-only or prefix-truncated index looks identical to a usable one in plain JDBC metadata but
 * cannot be relied on.</p>
 *
 * @param name the index name
 * @param keyParts the ordered key parts
 * @param unique whether the index enforces uniqueness
 * @param method the access method/type (e.g. {@code btree}, {@code hash}), or {@code null} when unknown
 * @param filterCondition the partial-index predicate, or {@code null} when the index covers every row
 * @param visibility whether the optimizer may use the index
 * @param validity whether the catalog reports the index as valid/usable
 * @param nullsNotDistinct whether a unique index was declared {@code NULLS NOT DISTINCT} (PostgreSQL 15+), so
 *     it rejects more than one {@code NULL} instead of treating every {@code NULL} as distinct
 * @param automatic whether the database created this index itself to back a primary key/unique constraint
 *     (Oracle {@code all_indexes.generated = 'Y'}) rather than the user creating it directly — {@code false}
 *     for every dialect that does not report this, since PostgreSQL/MySQL constraint-backing is instead
 *     identified by name/column match against the primary key ({@link TableModel#primaryKeyBackingIndex()})
 * @param partitioned whether the index itself is partitioned (Oracle {@code all_indexes.partitioned = 'YES'});
 *     always {@code false} elsewhere
 * @param specialized whether the index has a type this advisor does not model comparison semantics for
 *     (Oracle function-based, domain, bitmap, LOB, or index-organized-table indexes) — excluded from
 *     redundancy comparisons rather than compared as if it were an ordinary B-tree index
 */
record IndexModel(
        String name,
        List<IndexKeyPart> keyParts,
        boolean unique,
        String method,
        String filterCondition,
        Visibility visibility,
        Validity validity,
        boolean nullsNotDistinct,
        boolean automatic,
        boolean partitioned,
        boolean specialized) {

    enum Visibility {
        VISIBLE,
        INVISIBLE,
        UNKNOWN
    }

    enum Validity {
        VALID,
        INVALID,
        UNKNOWN
    }

    IndexModel {
        keyParts = List.copyOf(keyParts);
    }

    /** Convenience constructor for callers with no Oracle-only/PostgreSQL-15-only information. */
    IndexModel(
            String name,
            List<IndexKeyPart> keyParts,
            boolean unique,
            String method,
            String filterCondition,
            Visibility visibility,
            Validity validity) {
        this(name, keyParts, unique, method, filterCondition, visibility, validity, false, false, false, false);
    }

    /** A plain index with no vendor augmentation, as read from generic JDBC metadata. */
    static IndexModel of(String name, List<String> columns, boolean unique) {
        List<IndexKeyPart> parts = new ArrayList<>();
        for (String column : columns) {
            parts.add(IndexKeyPart.column(column, null));
        }
        return new IndexModel(name, parts, unique, null, null, Visibility.UNKNOWN, Validity.UNKNOWN);
    }

    List<String> columnNames() {
        return keyParts.stream().map(IndexKeyPart::columnName).toList();
    }

    /** The index's leading key part's column, or {@code null} for an empty or expression-led index. */
    String leadingColumn() {
        return keyParts.isEmpty() ? null : keyParts.get(0).columnName();
    }

    boolean partial() {
        return filterCondition != null && !filterCondition.isBlank();
    }

    boolean hasExpressionKeyPart() {
        return keyParts.stream().anyMatch(IndexKeyPart::isExpression);
    }

    boolean hasPrefixKeyPart() {
        return keyParts.stream().anyMatch(IndexKeyPart::isPrefix);
    }

    boolean invisible() {
        return visibility == Visibility.INVISIBLE;
    }

    boolean invalid() {
        return validity == Validity.INVALID;
    }

    /**
     * True when the optimizer can rely on this index for equality lookups on {@code columns} in that exact
     * order as the index's leading key parts.
     *
     * <p>Deliberately conservative: an invalid, invisible, partial or expression-led index is never counted,
     * and neither is a MySQL/MariaDB prefix key part, which indexes only the first N characters and so
     * cannot answer a full-value equality lookup on its own.</p>
     */
    boolean supportsLeadingEquality(List<String> columns) {
        if (columns.isEmpty() || keyParts.size() < columns.size() || !usable()) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            IndexKeyPart part = keyParts.get(i);
            if (part.isExpression() || part.isPrefix() || !part.matchesColumn(columns.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the first {@code columns.size()} key parts of this index are exactly {@code columns}, as a
     * set, in any order — Oracle's own documented guidance for what supports a composite foreign key: a pure
     * multi-column equality lookup (the shape every FK-support query, join, and cascading delete/update uses)
     * does not care which of the leading key parts binds to which column, since every one of them is bound by
     * equality at once. Every usability caveat {@link #supportsLeadingEquality} applies still applies:
     * invalid, invisible, partial, expression and prefix key parts are never counted.
     */
    boolean supportsLeadingEqualityAnyOrder(List<String> columns) {
        if (columns.isEmpty() || keyParts.size() < columns.size() || !usable()) {
            return false;
        }
        List<IndexKeyPart> leading = keyParts.subList(0, columns.size());
        if (leading.stream().anyMatch(part -> part.isExpression() || part.isPrefix())) {
            return false;
        }
        List<String> remaining = new ArrayList<>(columns);
        for (IndexKeyPart part : leading) {
            boolean matched = remaining.removeIf(part::matchesColumn);
            if (!matched) {
                return false;
            }
        }
        return remaining.isEmpty();
    }

    /**
     * True when this unique index genuinely enforces uniqueness over exactly {@code columns} (in any order:
     * uniqueness is order-independent), with no prefix, expression or partial semantics weakening it.
     */
    boolean enforcesUniquenessOver(List<String> columns) {
        if (!unique || !usable() || columns.isEmpty() || keyParts.size() != columns.size()) {
            return false;
        }
        if (hasExpressionKeyPart() || hasPrefixKeyPart()) {
            return false;
        }
        List<String> indexed = new ArrayList<>(columnNames());
        for (String column : columns) {
            boolean removed = indexed.removeIf(
                    indexedColumn -> indexedColumn != null && column != null && indexedColumn.equalsIgnoreCase(column));
            if (!removed) {
                return false;
            }
        }
        return indexed.isEmpty();
    }

    /** True when the index covers exactly {@code columns} in the same order, with plain key parts only. */
    boolean coversExactlyInOrder(List<String> columns) {
        if (keyParts.size() != columns.size()) {
            return false;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (!keyParts.get(i).matchesColumn(columns.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** True when nothing in the catalog marks this index as unusable by the optimizer. */
    boolean usable() {
        return !invalid() && !invisible() && !partial();
    }

    /**
     * True when two indexes have the same structural semantics — same ordered key parts (including prefix
     * lengths, expressions, direction and collation), same access method and same partial predicate — so one
     * really is a duplicate of the other rather than merely sharing a column prefix.
     */
    boolean sameSemanticsAs(IndexModel other) {
        return Objects.equals(normalizedMethod(), other.normalizedMethod())
                && Objects.equals(normalize(filterCondition), normalize(other.filterCondition))
                && visibility == other.visibility;
    }

    /** True when {@code this} index's key parts are a leading prefix of {@code other}'s (or identical). */
    boolean isKeyPrefixOf(IndexModel other) {
        if (keyParts.isEmpty() || keyParts.size() > other.keyParts.size()) {
            return false;
        }
        for (int i = 0; i < keyParts.size(); i++) {
            if (!sameKeyPart(keyParts.get(i), other.keyParts.get(i))) {
                return false;
            }
        }
        return true;
    }

    String describeKeyParts() {
        return keyParts.stream().map(IndexKeyPart::describe).toList().toString();
    }

    private static boolean sameKeyPart(IndexKeyPart left, IndexKeyPart right) {
        if (left.isExpression() != right.isExpression()) {
            return false;
        }
        if (left.isExpression()) {
            return Objects.equals(normalize(left.expression()), normalize(right.expression()));
        }
        return left.matchesColumn(right.columnName())
                && Objects.equals(left.prefixLength(), right.prefixLength())
                && Objects.equals(left.ascending(), right.ascending())
                && Objects.equals(normalize(left.collation()), normalize(right.collation()));
    }

    private String normalizedMethod() {
        return normalize(method);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
