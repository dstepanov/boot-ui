package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.spi.FlywayProvider;
import io.github.jdubois.bootui.spi.LiquibaseProvider;
import io.micronaut.core.annotation.Nullable;

/**
 * The single Flyway and Liquibase seams the adapter runs on, produced once by {@link BootUiEngineFactory}.
 *
 * <p>Two consumers need the same seam: the engine's {@code FlywayService} / {@code LiquibaseService}, which
 * answer the panels' reads and actions, and {@link MicronautPanelAvailability}, which asks whether a
 * migration is actually configured before advertising the panel at all. Each once built its own instance, so
 * the manifest and the panel agreed only because the two construction sites happened to be identical; one
 * shared instance makes them agree by identity instead.
 *
 * <p>Either component is {@code null} when its library is absent from the application's classpath — the
 * state the engine services render as the panel's honest unavailable reason. That nullability is why this
 * holder exists rather than two provider beans: a Micronaut factory method may not return {@code null} (the
 * container fails the injection with "InstantiatableBeanDefinition … returned null", and {@code @Nullable}
 * on the factory method does not change that), so "absent" cannot be modelled as "no bean" from a factory
 * that decides at runtime. The holder is always present and carries the absence inside it.
 *
 * <p>It holds the neutral SPI types, never the Micronaut-specific implementations, so nothing here links
 * Flyway or Liquibase classes in an application that does not have them.
 */
public record MicronautMigrationProviders(
        @Nullable FlywayProvider flyway, @Nullable LiquibaseProvider liquibase) {}
