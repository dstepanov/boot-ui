package io.github.jdubois.bootui.micronaut.liquibase;

import io.github.jdubois.bootui.core.dto.LiquibaseActionResult;
import io.github.jdubois.bootui.core.dto.LiquibaseChangeSetDto;
import io.github.jdubois.bootui.spi.LiquibaseDatabaseSnapshot;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.github.jdubois.bootui.spi.LiquibaseTarget;
import io.micronaut.context.BeanContext;
import io.micronaut.context.env.Environment;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.liquibase.LiquibaseConfigurationProperties;
import io.micronaut.liquibase.LiquibaseResourceAccessor;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import javax.sql.DataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.RanChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ResourceAccessor;

/**
 * Micronaut {@link LiquibaseProvider} over {@code micronaut-liquibase}'s per-datasource configuration.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code SpringLiquibase} reader and the Quarkus adapter's
 * {@code LiquibaseFactory} reader. Micronaut runs its migration through a migrator and does not publish the
 * {@link Liquibase} instances, so this provider builds one per configured datasource from the same inputs —
 * the change log, contexts, labels and change-log table names from
 * {@link LiquibaseConfigurationProperties}, over the matching {@code DataSource} bean and Micronaut's own
 * {@link LiquibaseResourceAccessor}, so a change log on the classpath or in the environment's config
 * locations resolves exactly as it does at startup.
 *
 * <p>Every read opens and closes its own connection, and each is independently fail-soft: an inaccessible
 * history table yields an empty list for that datasource rather than failing the panel.
 */
public final class MicronautLiquibaseProvider implements LiquibaseProvider {

    private final BeanContext beanContext;
    private final Environment environment;

    public MicronautLiquibaseProvider(BeanContext beanContext, Environment environment) {
        this.beanContext = beanContext;
        this.environment = environment;
    }

    @Override
    public boolean available() {
        return beanContext != null && !configurations().isEmpty();
    }

    @Override
    public List<LiquibaseDatabaseSnapshot> databases() {
        List<LiquibaseDatabaseSnapshot> databases = new ArrayList<>();
        for (LiquibaseConfigurationProperties configuration : configurations()) {
            databases.add(new LiquibaseDatabaseSnapshot(
                    configuration.getNameQualifier(),
                    readAppliedChangeSets(configuration),
                    readPendingChangeSets(configuration),
                    updateDisabledReason(configuration)));
        }
        return List.copyOf(databases);
    }

    @Override
    public List<LiquibaseTarget> targets() {
        List<LiquibaseTarget> targets = new ArrayList<>();
        for (LiquibaseConfigurationProperties configuration : configurations()) {
            targets.add(new LiquibaseTarget(configuration.getNameQualifier(), updateDisabledReason(configuration)));
        }
        return List.copyOf(targets);
    }

    @Override
    public LiquibaseActionResult update(String name) throws Exception {
        LiquibaseConfigurationProperties configuration = configurations().stream()
                .filter(candidate -> candidate.getNameQualifier().equals(name))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("No Liquibase configuration named '" + name + "' is available."));
        try (Connection connection = connection(configuration);
                Liquibase liquibase = liquibase(configuration, connection)) {
            Contexts contexts = contexts(configuration);
            LabelExpression labels = labels(configuration);
            int before = liquibase.listUnrunChangeSets(contexts, labels).size();
            liquibase.update(contexts, labels);
            int after = liquibase.listUnrunChangeSets(contexts, labels).size();
            int applied = Math.max(0, before - after);
            String message = applied == 0
                    ? "Liquibase database is already up to date."
                    : "Liquibase applied " + applied + " change set(s).";
            return new LiquibaseActionResult("success", message, name, before, after, applied, List.of());
        }
    }

    private Collection<LiquibaseConfigurationProperties> configurations() {
        if (beanContext == null) {
            return List.of();
        }
        try {
            return beanContext.getBeansOfType(LiquibaseConfigurationProperties.class).stream()
                    .filter(LiquibaseConfigurationProperties::isEnabled)
                    .filter(configuration -> configuration.getChangeLog() != null)
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /**
     * A configuration that reached this point has a change log and an enabled datasource, so the update
     * action is always available — mirroring the Quarkus adapter, whose factory only exists under the same
     * conditions. The Spring-only "no datasource / no change log" reasons cannot arise here.
     */
    private static String updateDisabledReason(LiquibaseConfigurationProperties configuration) {
        return null;
    }

    private List<LiquibaseChangeSetDto> readAppliedChangeSets(LiquibaseConfigurationProperties configuration) {
        try (Connection connection = connection(configuration);
                Liquibase liquibase = liquibase(configuration, connection)) {
            List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
            for (RanChangeSet ranChangeSet : liquibase.getDatabase().getRanChangeSetList()) {
                changeSets.add(toChangeSetDto(ranChangeSet));
            }
            return List.copyOf(changeSets);
        } catch (Exception ex) {
            // Fail closed for this datasource: an inaccessible history table yields an empty list.
            return List.of();
        }
    }

    private List<LiquibaseChangeSetDto> readPendingChangeSets(LiquibaseConfigurationProperties configuration) {
        try (Connection connection = connection(configuration);
                Liquibase liquibase = liquibase(configuration, connection)) {
            List<LiquibaseChangeSetDto> changeSets = new ArrayList<>();
            for (ChangeSet changeSet : liquibase.listUnrunChangeSets(contexts(configuration), labels(configuration))) {
                changeSets.add(toPendingChangeSetDto(changeSet));
            }
            return List.copyOf(changeSets);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private Connection connection(LiquibaseConfigurationProperties configuration) throws Exception {
        DataSource dataSource =
                beanContext.getBean(DataSource.class, Qualifiers.byName(configuration.getNameQualifier()));
        return dataSource.getConnection();
    }

    private Liquibase liquibase(LiquibaseConfigurationProperties configuration, Connection connection)
            throws Exception {
        Database database =
                DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        applyNames(configuration, database);
        ResourceAccessor resourceAccessor = new LiquibaseResourceAccessor(environment);
        return new Liquibase(configuration.getChangeLog(), resourceAccessor, database);
    }

    /**
     * Applies the change-log table and schema names the application configured, so the panel reads the same
     * history table the application's own migration writes rather than Liquibase's defaults.
     */
    private static void applyNames(LiquibaseConfigurationProperties configuration, Database database) throws Exception {
        if (configuration.getDatabaseChangeLogTable() != null) {
            database.setDatabaseChangeLogTableName(configuration.getDatabaseChangeLogTable());
        }
        if (configuration.getDatabaseChangeLogLockTable() != null) {
            database.setDatabaseChangeLogLockTableName(configuration.getDatabaseChangeLogLockTable());
        }
        if (configuration.getLiquibaseSchema() != null) {
            database.setLiquibaseSchemaName(configuration.getLiquibaseSchema());
        }
        if (configuration.getLiquibaseTablespace() != null) {
            database.setLiquibaseTablespaceName(configuration.getLiquibaseTablespace());
        }
        if (configuration.getDefaultSchema() != null) {
            database.setDefaultSchemaName(configuration.getDefaultSchema());
        }
    }

    private static Contexts contexts(LiquibaseConfigurationProperties configuration) {
        return new Contexts(configuration.getContexts());
    }

    private static LabelExpression labels(LiquibaseConfigurationProperties configuration) {
        return new LabelExpression(configuration.getLabels());
    }

    private static LiquibaseChangeSetDto toChangeSetDto(RanChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getChangeLog(),
                changeSet.getDescription(),
                changeSet.getComments(),
                changeSet.getExecType() == null ? null : changeSet.getExecType().name(),
                nullSafeToInstant(changeSet.getDateExecuted()),
                changeSet.getOrderExecuted(),
                changeSet.getLastCheckSum() == null
                        ? null
                        : changeSet.getLastCheckSum().toString(),
                changeSet.getTag(),
                changeSet.getDeploymentId(),
                changeSet.getContextExpression() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextExpression().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    private static LiquibaseChangeSetDto toPendingChangeSetDto(ChangeSet changeSet) {
        return new LiquibaseChangeSetDto(
                changeSet.getId(),
                changeSet.getAuthor(),
                changeSet.getFilePath(),
                changeSet.getDescription(),
                changeSet.getComments(),
                "PENDING",
                null,
                null,
                null,
                null,
                null,
                changeSet.getContextFilter() == null
                        ? List.of()
                        : List.copyOf(changeSet.getContextFilter().getContexts()),
                changeSet.getLabels() == null
                        ? List.of()
                        : List.copyOf(changeSet.getLabels().getLabels()));
    }

    private static String nullSafeToInstant(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }
}
