package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorRuleResultDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared plumbing for the Hibernate ↔ physical schema cross-reference rules: they all skip (with a reason)
 * when either half is missing, and they all evaluate only entities whose physical table could be resolved
 * unambiguously — an entity with no explicit {@code @Table(name)}, or one whose name matches tables in several
 * readable datasources, is skipped rather than attributed to the wrong database.
 */
abstract class AbstractHibernateCrossReferenceRule extends AbstractDatabaseAdvisorRule {

    AbstractHibernateCrossReferenceRule(DatabaseAdvisorRuleDefinition definition) {
        super(definition);
    }

    /** Adds any findings for one resolved entity/table pair. */
    abstract void checkEntity(SchemaSnapshot schema, TableModel table, MappedEntityFacts entity, List<String> details);

    @Override
    DatabaseAdvisorRuleResultDto evaluateRule(DatabaseAdvisorContext context) {
        if (!context.hibernateAvailable()) {
            return skipped("No EntityManagerFactory/Hibernate metamodel is available to cross-reference.");
        }
        if (context.availableSchemas().isEmpty()) {
            return skipped("No physical schema could be read to cross-reference against.");
        }
        List<String> details = new ArrayList<>();
        for (MappedEntityFacts entity : context.hibernateEntities()) {
            MappedTableResolution resolution = MappedTableResolution.resolve(context, entity);
            if (!resolution.resolved()) {
                continue;
            }
            if (!resolution.table().metadata().complete() && requiresCompleteMetadata()) {
                continue;
            }
            checkEntity(resolution.schema(), resolution.table(), entity, details);
        }
        return violation(details);
    }

    /**
     * Whether the rule needs fully-read table metadata. Rules that conclude something is <em>absent</em> must
     * not run against a table whose metadata was truncated or partly unreadable.
     */
    boolean requiresCompleteMetadata() {
        return true;
    }
}
