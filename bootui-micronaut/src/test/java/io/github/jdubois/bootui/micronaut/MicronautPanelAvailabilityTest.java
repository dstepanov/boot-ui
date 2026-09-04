package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.PanelDto;
import io.github.jdubois.bootui.core.dto.PanelsReport;
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
