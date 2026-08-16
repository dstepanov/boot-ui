package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/**
 * Resolves one mapped entity's physical table across every readable datasource, honestly.
 *
 * <p>An entity is only matched when its physical name is <em>known</em> (an explicit {@code @Table(name)}),
 * and only when exactly one table matches: with several datasources or several schemas in play, a bare table
 * name can legitimately exist more than once, and picking the first match would attribute findings to the
 * wrong database. An ambiguous match therefore reports {@link Status#AMBIGUOUS} and the rule skips that
 * entity instead of guessing.</p>
 */
record MappedTableResolution(Status status, SchemaSnapshot schema, TableModel table, String detail) {

    enum Status {
        RESOLVED,
        NOT_MAPPED,
        NOT_FOUND,
        AMBIGUOUS
    }

    boolean resolved() {
        return status == Status.RESOLVED;
    }

    static MappedTableResolution resolve(DatabaseAdvisorContext context, MappedEntityFacts entity) {
        String tableName = entity.explicitTableName();
        if (tableName == null || tableName.isBlank()) {
            return new MappedTableResolution(Status.NOT_MAPPED, null, null, null);
        }
        SchemaSnapshot matchedSchema = null;
        TableModel matchedTable = null;
        int matches = 0;
        for (SchemaSnapshot schema : context.availableSchemas()) {
            List<TableModel> candidates =
                    schema.tablesNamed(entity.explicitCatalog(), entity.explicitSchema(), tableName);
            matches += candidates.size();
            if (!candidates.isEmpty() && matchedTable == null) {
                matchedSchema = schema;
                matchedTable = candidates.get(0);
            }
        }
        if (matches == 0) {
            return new MappedTableResolution(Status.NOT_FOUND, null, null, null);
        }
        if (matches > 1) {
            return new MappedTableResolution(
                    Status.AMBIGUOUS,
                    null,
                    null,
                    matches + " physical tables named " + qualify(entity) + " were found across the readable "
                            + "datasources, so this entity cannot be attributed to one of them.");
        }
        return new MappedTableResolution(Status.RESOLVED, matchedSchema, matchedTable, null);
    }

    private static String qualify(MappedEntityFacts entity) {
        StringBuilder name = new StringBuilder();
        if (entity.explicitSchema() != null && !entity.explicitSchema().isBlank()) {
            name.append(entity.explicitSchema()).append('.');
        }
        return name.append(entity.explicitTableName()).toString();
    }
}
