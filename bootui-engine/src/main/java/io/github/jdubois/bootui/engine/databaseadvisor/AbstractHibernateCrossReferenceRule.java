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
 *
 * <p>An entity can split its mapped items across more than one physical table via {@code @SecondaryTable}.
 * {@link #checkEntity} therefore does not receive one pre-resolved table; it receives the entity's own
 * (primary-table) resolution and resolves each item's actual table through {@link #resolveItemTable}, which
 * falls back to the primary table when the item declares no {@code table=} override.</p>
 */
abstract class AbstractHibernateCrossReferenceRule extends AbstractDatabaseAdvisorRule {

    AbstractHibernateCrossReferenceRule(DatabaseAdvisorRuleDefinition definition) {
        super(definition);
    }

    /** Adds any findings for one entity, given its resolved primary-table facts. */
    abstract void checkEntity(
            DatabaseAdvisorContext context,
            MappedTableResolution primary,
            MappedEntityFacts entity,
            List<String> details);

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
            checkEntity(context, resolution, entity, details);
        }
        return violation(details);
    }

    /**
     * Resolves which physical table one mapped item (a column, join column set, or unique constraint) belongs
     * to: {@code primary} when the item declares no explicit {@code table=} override, or the named
     * {@code @SecondaryTable} otherwise. Returns a resolution with {@link MappedTableResolution#resolved()}
     * {@code false} when the override does not match a declared secondary table, that secondary table cannot
     * be found unambiguously in the physical schema, or (when {@link #requiresCompleteMetadata()}) its metadata
     * was not read completely — skip that item rather than guess or risk a false "absent" finding.
     */
    final MappedTableResolution resolveItemTable(
            DatabaseAdvisorContext context,
            MappedEntityFacts entity,
            MappedTableResolution primary,
            String itemTableName) {
        MappedTableResolution resolution = itemTableName == null || itemTableName.isBlank()
                ? primary
                : MappedTableResolution.resolveSecondary(context, entity, itemTableName);
        if (!resolution.resolved()) {
            return resolution;
        }
        if (requiresCompleteMetadata() && !resolution.table().metadata().complete()) {
            return new MappedTableResolution(MappedTableResolution.Status.NOT_FOUND, null, null, null);
        }
        return resolution;
    }

    /**
     * Whether the rule needs fully-read table metadata. Rules that conclude something is <em>absent</em> must
     * not run against a table whose metadata was truncated or partly unreadable.
     */
    boolean requiresCompleteMetadata() {
        return true;
    }
}
