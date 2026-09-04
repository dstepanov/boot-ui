package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
import io.github.jdubois.bootui.engine.panel.BootUiPanels;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the panel manifest: the platform discriminator the shared UI branches on, the availability of the
 * panels this adapter actually wires, and the honesty of the reasons given for the rest.
 */
class MicronautPanelAvailabilityTest {

    @Test
    void reportsTheMicronautPlatformAndTheWholeSharedRegistry() {
        PanelsReport manifest = manifest(Map.of());

        assertThat(manifest.platform()).isEqualTo(PanelsReport.PLATFORM_MICRONAUT);
        assertThat(manifest.panels()).hasSameSizeAs(BootUiPanels.all());
    }

    @Test
    void marksTheWiredPanelsAvailable() {
        PanelsReport manifest = manifest(Map.of());

        assertThat(available(manifest, BootUiPanels.BEANS)).isTrue();
        assertThat(available(manifest, BootUiPanels.MAPPINGS)).isTrue();
        assertThat(available(manifest, BootUiPanels.CONFIG)).isTrue();
        assertThat(available(manifest, BootUiPanels.LOGGERS)).isTrue();
        assertThat(available(manifest, BootUiPanels.HEALTH)).isTrue();
    }

    @Test
    void distinguishesNotApplicableFromNotYetPorted() {
        PanelsReport manifest = manifest(Map.of());

        assertThat(panel(manifest, BootUiPanels.SPRING_SECURITY).unavailableReason())
                .startsWith("Not applicable on Micronaut:")
                .contains("micronaut-security");
        assertThat(panel(manifest, BootUiPanels.KAFKA).unavailableReason())
                .isEqualTo("Not yet available on Micronaut.");
    }

    @Test
    void reportsTheConfigurationPanelAsInherentlyReadOnly() {
        PanelDto config = panel(manifest(Map.of()), BootUiPanels.CONFIG);

        assertThat(config.readOnly()).isTrue();
        assertThat(config.readOnlyReason()).contains("Runtime config overrides are not available on Micronaut");
    }

    @Test
    void reflectsTheLiveAccessPolicy() {
        PanelsReport manifest = manifest(Map.of("bootui.panels.beans.enabled", "false", "bootui.read-only", "true"));

        assertThat(panel(manifest, BootUiPanels.BEANS).enabled()).isFalse();
        PanelDto loggers = panel(manifest, BootUiPanels.LOGGERS);
        assertThat(loggers.readOnly()).isTrue();
        assertThat(loggers.readOnlyReason()).isEqualTo("BootUI is read-only via bootui.read-only=true");
    }

    /**
     * {@code micronaut-flyway} and {@code micronaut-liquibase} are {@code provided} dependencies of this module,
     * so both are on this test classpath — and nothing configures them. A present library is not a configured
     * one: the panels stay dark, and each reason names the configuration that would light it up rather than
     * the dependency that is already there.
     */
    @Test
    void keepsFlywayAndLiquibaseDarkWhileTheirLibrariesAreUnconfigured() {
        PanelsReport manifest = manifest(Map.of());

        PanelDto flyway = panel(manifest, BootUiPanels.FLYWAY);
        assertThat(flyway.available()).isFalse();
        assertThat(flyway.unavailableReason())
                .startsWith("Not available: micronaut-flyway is on the classpath")
                .contains("flyway.datasources.<name>");
        PanelDto liquibase = panel(manifest, BootUiPanels.LIQUIBASE);
        assertThat(liquibase.available()).isFalse();
        assertThat(liquibase.unavailableReason())
                .startsWith("Not available: micronaut-liquibase is on the classpath")
                .contains("liquibase.datasources.<name>.change-log");
    }

    /** A migration configuration that names no datasource bean is not a configured migration either. */
    @Test
    void keepsFlywayAndLiquibaseDarkWhenTheirConfigurationHasNoDatasourceBehindIt() {
        PanelsReport manifest = manifest(Map.of(
                "flyway.datasources.default.enabled", "true",
                "liquibase.datasources.default.change-log", "classpath:db/bootui-test-changelog.xml"));

        assertThat(available(manifest, BootUiPanels.FLYWAY)).isFalse();
        assertThat(available(manifest, BootUiPanels.LIQUIBASE)).isFalse();
    }

    /**
     * The positive half: an enabled migration configured against a datasource bean lights both panels up, and
     * the engine services — the ones the panel's reads and actions actually run on — agree with the manifest.
     */
    @Test
    void lightsFlywayAndLiquibaseOnceAMigrationIsConfiguredAgainstADatasource() {
        Map<String, Object> configured = Map.of(
                TestDataSourceFactory.PROPERTY,
                "true",
                "flyway.datasources.default.enabled",
                "true",
                "liquibase.datasources.default.change-log",
                "classpath:db/bootui-test-changelog.xml");
        try (ApplicationContext context = ApplicationContext.run(configured, "test")) {
            MicronautPanelAvailability availability = context.getBean(MicronautPanelAvailability.class);

            assertThat(availability.isPanelAvailable(BootUiPanels.FLYWAY)).isTrue();
            assertThat(availability.panelUnavailableReason(BootUiPanels.FLYWAY)).isNull();
            assertThat(availability.isPanelAvailable(BootUiPanels.LIQUIBASE)).isTrue();
            assertThat(availability.panelUnavailableReason(BootUiPanels.LIQUIBASE))
                    .isNull();

            assertThat(context.getBean(FlywayService.class).report().flywayPresent())
                    .isTrue();
            assertThat(context.getBean(LiquibaseService.class).report().liquibasePresent())
                    .isTrue();
        }
    }

    private static PanelsReport manifest(Map<String, Object> properties) {
        try (ApplicationContext context = ApplicationContext.run(properties, "test")) {
            return context.getBean(MicronautPanelAvailability.class).manifest();
        }
    }

    private static PanelDto panel(PanelsReport manifest, String id) {
        return manifest.panels().stream()
                .filter(panel -> panel.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No panel " + id + " in the manifest"));
    }

    private static boolean available(PanelsReport manifest, String id) {
        return panel(manifest, id).available();
    }
}
