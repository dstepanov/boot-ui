package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import java.util.ArrayList;
import java.util.List;

/**
 * Two indexes on the same table where one is genuinely redundant: its ordered key parts are a leading prefix
 * of the other's <em>and</em> both share the same semantics, so the longer index answers everything the
 * shorter one does.
 *
 * <p>The previous "same leading column" comparison flagged pairs that are not interchangeable at all. This
 * one requires every key part to match — column, direction, collation, prefix length, expression — plus the
 * same access method, the same partial predicate and the same visibility, and it refuses to suggest dropping
 * an index that carries a semantic guarantee the other does not, or one this advisor cannot fully model:</p>
 *
 * <ul>
 *   <li>a <strong>unique</strong> index is never reported as the redundant one: {@code unique(a)} and
 *       {@code index(a, b)} look like a prefix pair but only the first enforces uniqueness of {@code a};</li>
 *   <li>the <strong>primary key's backing index</strong> is left out entirely — dropping it is not an
 *       option, and the redundant-unique-index case belongs to {@code DB-SCHEMA-005};</li>
 *   <li>a <strong>partial or invalid</strong> index is left out, since it is not equivalent to a full one;</li>
 *   <li>on Oracle, an <strong>automatically created (constraint-backing), partitioned, or specialized</strong>
 *       index — function-based, domain, bitmap, LOB, or index-organized-table — is left out: dropping a
 *       constraint's own backing index is not the user's decision, and this advisor's leading-prefix
 *       reasoning is written for ordinary B-tree indexes, not proven complete for those semantics.</li>
 * </ul>
 */
final class DuplicateIndexRule extends AbstractDatabaseAdvisorRule {

    DuplicateIndexRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-SCHEMA-003",
                "Duplicate/redundant indexes",
                DatabaseAdvisorCategory.SCHEMA,
                DatabaseAdvisorRuleSupport.LOW,
                "Detects a non-unique index whose ordered key parts (columns, direction, collation, prefix "
                        + "length, expressions) are a leading prefix of another index on the same table with the "
                        + "same access method, predicate and visibility. Unique indexes, primary key backing "
                        + "indexes, partial/invalid indexes, and (Oracle) automatic/partitioned/specialized "
                        + "indexes are excluded.",
                "Every additional index slows down INSERT/UPDATE/DELETE and consumes storage. When one index's "
                        + "key parts are a leading prefix of another's with identical semantics, the shorter one is "
                        + "usually redundant; review both definitions, and any index hints or constraints relying "
                        + "on them, before removing either.",
                "https://use-the-index-luke.com/sql/dml"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        List<String> details = new ArrayList<>();
        for (SchemaSnapshot schema : context.availableSchemas()) {
            for (TableModel table : DatabaseAdvisorContext.analyzableTables(schema)) {
                if (!table.metadata().indexesRead()) {
                    continue;
                }
                checkTable(schema, table, details);
            }
        }
        return violation(details);
    }

    private void checkTable(SchemaSnapshot schema, TableModel table, List<String> details) {
        IndexModel primaryKeyIndex = table.primaryKeyBackingIndex();
        List<IndexModel> candidates = table.indexes().stream()
                .filter(index -> index != primaryKeyIndex)
                .filter(index -> !index.partial()
                        && !index.invalid()
                        && !index.keyParts().isEmpty()
                        && !index.automatic()
                        && !index.partitioned()
                        && !index.specialized())
                .toList();
        for (IndexModel shorter : candidates) {
            if (shorter.unique()) {
                // A unique index enforces a constraint the covering index does not; never suggest dropping it.
                continue;
            }
            for (IndexModel longer : candidates) {
                if (shorter == longer || !shorter.sameSemanticsAs(longer) || !shorter.isKeyPrefixOf(longer)) {
                    continue;
                }
                if (shorter.keyParts().size() == longer.keyParts().size()
                        && shorter.name().compareToIgnoreCase(longer.name()) >= 0) {
                    // Exact duplicates would otherwise be reported twice, once from each side.
                    continue;
                }
                details.add(schema.dataSourceName() + ": " + table.qualifiedName() + " index " + shorter.name() + " "
                        + shorter.describeKeyParts() + " is a leading prefix of " + longer.name() + " "
                        + longer.describeKeyParts() + " with the same semantics.");
                break;
            }
        }
    }
}
