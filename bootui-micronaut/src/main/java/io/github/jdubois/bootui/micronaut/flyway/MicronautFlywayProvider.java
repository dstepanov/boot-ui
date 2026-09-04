package io.github.jdubois.bootui.micronaut.flyway;

import io.github.jdubois.bootui.core.dto.FlywayMigrationDto;
import io.github.jdubois.bootui.spi.FlywayCleanOutcome;
import io.github.jdubois.bootui.spi.FlywayDatabaseSnapshot;
import io.github.jdubois.bootui.spi.FlywayMigrateOutcome;
import io.github.jdubois.bootui.spi.FlywayMigrationSnapshot;
import io.github.jdubois.bootui.spi.FlywayProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.flyway.FlywayConfigurationProperties;
import io.micronaut.inject.qualifiers.Qualifiers;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.CleanResult;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Micronaut {@link FlywayProvider} over {@code micronaut-flyway}'s per-datasource configuration.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code Flyway} bean reader and the Quarkus adapter's
 * {@code FlywayContainer} reader. Micronaut runs migrations through a migrator rather than publishing the
 * {@link Flyway} instances themselves, so this provider rebuilds one per configured datasource from exactly
 * the inputs {@code micronaut-flyway} uses — its {@link FlywayConfigurationProperties#getFluentConfiguration()
 * fluent configuration} plus the matching {@code DataSource} bean. That means the migration history the panel
 * shows, and any migration it runs, use the same configuration the application's own startup migration does.
 *
 * <p>Only enabled configurations are reported. A configuration whose datasource cannot be resolved is
 * skipped rather than reported as broken: the application's own startup already surfaced that.
 */
public final class MicronautFlywayProvider implements FlywayProvider {

    static final String CLEAN_DISABLED_BY_FLYWAY =
            "Flyway clean is disabled by Flyway configuration. Set flyway.datasources.<name>.clean-disabled=false"
                    + " to allow it.";

    private final BeanContext beanContext;

    public MicronautFlywayProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        return beanContext != null && !configurations().isEmpty();
    }

    @Override
    public List<FlywayDatabaseSnapshot> report() {
        List<FlywayDatabaseSnapshot> snapshots = new ArrayList<>();
        for (FlywayConfigurationProperties configuration : configurations()) {
            flywayFor(configuration).ifPresent(flyway -> snapshots.add(toSnapshot(configuration, flyway)));
        }
        return List.copyOf(snapshots);
    }

    @Override
    public Optional<String> actionsBlockedReason() {
        return Optional.empty();
    }

    @Override
    public List<String> actionTargets() {
        List<String> targets = new ArrayList<>();
        for (FlywayConfigurationProperties configuration : configurations()) {
            targets.add(configuration.getNameQualifier());
        }
        return List.copyOf(targets);
    }

    @Override
    public Optional<String> cleanDisabledReason(String name) {
        return findFlyway(name).map(MicronautFlywayProvider::cleanDisabledReason);
    }

    @Override
    public FlywayMigrateOutcome migrate(String name) {
        Flyway flyway = findFlyway(name).orElse(null);
        if (flyway == null) {
            return new FlywayMigrateOutcome(
                    false, 0, List.of(), true, "No Flyway configuration matched the requested datasource.");
        }
        try {
            MigrateResult result = flyway.migrate();
            return new FlywayMigrateOutcome(
                    result.success, result.migrationsExecuted, nullSafeList(result.warnings), false, null);
        } catch (FlywayException ex) {
            return new FlywayMigrateOutcome(false, 0, List.of(), true, ex.getMessage());
        }
    }

    @Override
    public FlywayCleanOutcome clean(String name) {
        Flyway flyway = findFlyway(name).orElse(null);
        if (flyway == null) {
            return new FlywayCleanOutcome(
                    List.of(), List.of(), List.of(), true, "No Flyway configuration matched the requested datasource.");
        }
        try {
            CleanResult result = flyway.clean();
            return new FlywayCleanOutcome(
                    nullSafeList(result.schemasCleaned),
                    nullSafeList(result.schemasDropped),
                    nullSafeList(result.warnings),
                    false,
                    null);
        } catch (FlywayException ex) {
            return new FlywayCleanOutcome(List.of(), List.of(), List.of(), true, ex.getMessage());
        }
    }

    private Collection<FlywayConfigurationProperties> configurations() {
        if (beanContext == null) {
            return List.of();
        }
        try {
            return beanContext.getBeansOfType(FlywayConfigurationProperties.class).stream()
                    .filter(FlywayConfigurationProperties::isEnabled)
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Optional<Flyway> findFlyway(String name) {
        for (FlywayConfigurationProperties configuration : configurations()) {
            if (configuration.getNameQualifier().equals(name)) {
                return flywayFor(configuration);
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the Flyway instance for one configured datasource, the same way {@code micronaut-flyway} does:
     * its fluent configuration bound to the named {@code DataSource} bean.
     */
    private Optional<Flyway> flywayFor(FlywayConfigurationProperties configuration) {
        try {
            DataSource dataSource =
                    beanContext.getBean(DataSource.class, Qualifiers.byName(configuration.getNameQualifier()));
            return Optional.of(configuration
                    .getFluentConfiguration()
                    .dataSource(dataSource)
                    .load());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static FlywayDatabaseSnapshot toSnapshot(FlywayConfigurationProperties configuration, Flyway flyway) {
        MigrationInfo[] all;
        try {
            all = flyway.info().all();
        } catch (Exception ex) {
            all = new MigrationInfo[0];
        }
        List<FlywayMigrationSnapshot> migrations = new ArrayList<>(all.length);
        for (MigrationInfo info : all) {
            MigrationState state = info.getState();
            boolean applied = state != null && state.isApplied();
            boolean pending = state == MigrationState.PENDING;
            migrations.add(new FlywayMigrationSnapshot(toMigrationDto(info), applied, pending));
        }
        String cleanDisabledReason = cleanDisabledReason(flyway);
        return new FlywayDatabaseSnapshot(
                configuration.getNameQualifier(),
                migrations,
                true,
                null,
                cleanDisabledReason == null,
                cleanDisabledReason);
    }

    private static FlywayMigrationDto toMigrationDto(MigrationInfo info) {
        MigrationState state = info.getState();
        return new FlywayMigrationDto(
                info.getType() == null ? null : info.getType().name(),
                nullSafeToString(info.getVersion()),
                info.getDescription(),
                info.getScript(),
                state == null ? null : state.getDisplayName(),
                info.getInstalledBy(),
                nullSafeToInstant(info.getInstalledOn()),
                info.getInstalledRank(),
                info.getExecutionTime(),
                info.getChecksum());
    }

    private static String cleanDisabledReason(Flyway flyway) {
        var configuration = flyway.getConfiguration();
        return configuration != null && configuration.isCleanDisabled() ? CLEAN_DISABLED_BY_FLYWAY : null;
    }

    private static String nullSafeToString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullSafeToInstant(Date date) {
        return date == null ? null : Instant.ofEpochMilli(date.getTime()).toString();
    }

    private static List<String> nullSafeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
