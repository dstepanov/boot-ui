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
 */
record IndexModel(
        String name,
        List<IndexKeyPart> keyParts,
        boolean unique,
        String method,
        String filterCondition,
        Visibility visibility,
        Validity validity) {

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
