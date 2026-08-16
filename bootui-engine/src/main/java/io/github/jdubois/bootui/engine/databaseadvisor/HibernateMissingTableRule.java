package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedSecondaryTableFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Cross-references mapped entities that declare an explicit {@code @Table(name=...)} against the physical
 * schema: a mapped table that is simply absent usually points to a stale entity, a missing migration, or the
 * wrong datasource/persistence-unit wiring.
 *
 * <p>Matching honors the declared {@code catalog}/{@code schema} when the entity gives them, so an entity
 * mapped to {@code reporting.orders} is not silently satisfied by an {@code app.orders} in another schema.
 * Entities relying on the default naming strategy are not evaluated (their physical name is not guessed), and
 * a name that matches tables in more than one readable datasource is reported as ambiguous rather than
 * resolved arbitrarily. Every declared {@code @SecondaryTable} is checked the same way, in addition to the
 * primary table.</p>
 */
final class HibernateMissingTableRule extends AbstractDatabaseAdvisorRule {

    HibernateMissingTableRule() {
        super(new DatabaseAdvisorRuleDefinition(
                "DB-HIB-002",
                "Mapped entity table not found in the physical schema",
                DatabaseAdvisorCategory.HIBERNATE_MAPPING,
                DatabaseAdvisorRuleSupport.MEDIUM,
                "Cross-references entities with an explicit @Table(name=...) and every declared "
                        + "@SecondaryTable — honoring the declared catalog/schema — against the physical "
                        + "schema's tables across every readable datasource.",
                "Verify the entity is mapped to the correct persistence unit/datasource, that a pending "
                        + "migration creates the table, or that the entity is stale and should be removed.",
                "https://jakarta.ee/specifications/persistence/3.2/jakarta-persistence-spec-3.2.html"));
    }

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        List<SchemaSnapshot> schemas = context.availableSchemas();
        if (schemas.isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        if (schemas.stream().anyMatch(SchemaSnapshot::truncated)) {
            // A truncated table list would make every unread table look like a missing one.
            return skipped("The table list was truncated for at least one datasource, so a missing mapped table "
                    + "cannot be distinguished from an unread one.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            MappedTableResolution resolution = MappedTableResolution.resolve(context, entity);
            if (resolution.status() == MappedTableResolution.Status.NOT_FOUND) {
                details.add(entity.entityName() + " is mapped to table " + entity.qualifiedTableName()
                        + ", which was not found in any readable datasource.");
            }
            for (MappedSecondaryTableFacts secondaryTable : entity.secondaryTables()) {
                MappedTableResolution secondaryResolution =
                        MappedTableResolution.resolveSecondary(context, entity, secondaryTable.name());
                if (secondaryResolution.status() == MappedTableResolution.Status.NOT_FOUND) {
                    details.add(entity.entityName() + " declares @SecondaryTable " + secondaryTable.qualifiedName()
                            + ", which was not found in any readable datasource.");
                }
            }
        }
        return violation(details);
    }
}
