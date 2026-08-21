package io.github.jdubois.bootui.engine.databaseadvisor;

import io.github.jdubois.bootui.core.dto.SqlTraceEntryDto;
import io.github.jdubois.bootui.engine.hibernate.HibernateSchemaBridge.MappedEntityFacts;
import java.util.List;

/**
 * Everything a Database Advisor rule needs: the physical schema read from every discovered
 * {@code DataSource}, plus (when available) the host application's mapped Hibernate entities.
 *
 * <p>{@code hibernateAvailable} is {@code false} whenever no {@code EntityManagerFactory}/Hibernate
 * metamodel could be read for the persistence unit(s) sharing these datasources; the Hibernate
 * cross-reference rules must skip (not silently drop) in that case.</p>
 *
 * <p>{@code observedStatements} carries the statements the SQL Trace panel has already retained, so a rule
 * can reason about how the application actually talks to the database rather than only about its schema. It
 * is empty whenever SQL tracing is off or nothing has run yet, and a rule reading it must skip in that case:
 * absence of captured evidence is not evidence of a healthy application.</p>
 */
record DatabaseAdvisorContext(
        List<SchemaSnapshot> schemas,
        boolean hibernateAvailable,
        List<MappedEntityFacts> hibernateEntities,
        List<SqlTraceEntryDto> observedStatements) {

    DatabaseAdvisorContext {
        schemas = List.copyOf(schemas);
        hibernateEntities = List.copyOf(hibernateEntities);
        observedStatements = observedStatements == null ? List.of() : List.copyOf(observedStatements);
    }

    DatabaseAdvisorContext(
            List<SchemaSnapshot> schemas, boolean hibernateAvailable, List<MappedEntityFacts> hibernateEntities) {
        this(schemas, hibernateAvailable, hibernateEntities, List.of());
    }

    List<SchemaSnapshot> availableSchemas() {
        return schemas.stream().filter(SchemaSnapshot::available).toList();
    }

    /** Every readable schema of one dialect family, for the vendor rules. */
    List<SchemaSnapshot> schemasOf(Dialect dialect) {
        return availableSchemas().stream()
                .filter(schema -> schema.dialect() == dialect)
                .toList();
    }

    /** Every readable MySQL or MariaDB schema; the two share the same {@code information_schema} rules. */
    List<SchemaSnapshot> mySqlFamilySchemas() {
        return availableSchemas().stream()
                .filter(schema -> schema.dialect().isMySqlFamily())
                .toList();
    }

    int tableCount() {
        return availableSchemas().stream()
                .mapToInt(schema -> schema.tables().size())
                .sum();
    }

    /**
     * The tables a schema-hygiene rule should evaluate: PostgreSQL child partitions are excluded because they
     * inherit their structure from the partitioned parent, which is analyzed in their place — otherwise one
     * missing index on a monthly-partitioned table would be reported once per month.
     */
    static List<TableModel> analyzableTables(SchemaSnapshot schema) {
        return schema.tables().stream().filter(table -> !table.partitionChild()).toList();
    }
}
