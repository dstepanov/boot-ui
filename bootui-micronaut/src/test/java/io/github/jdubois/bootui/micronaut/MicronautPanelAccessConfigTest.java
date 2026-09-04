package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourcePropertyResolver;
import io.micronaut.core.value.PropertyResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the per-panel access policy, including the exact rejection messages, which are part of the shared
 * cross-adapter contract the conformance suite asserts byte-for-byte.
 */
class MicronautPanelAccessConfigTest {

    @Test
    void panelsAreEnabledAndWritableByDefault() {
        MicronautPanelAccessConfig access = access(Map.of());

        assertThat(access.isPanelEnabled("loggers")).isTrue();
        assertThat(access.isPanelReadOnly("loggers")).isFalse();
    }

    @Test
    void aDisabledPanelReportsTheCanonicalReason() {
        MicronautPanelAccessConfig access = access(Map.of("bootui.panels.loggers.enabled", "false"));

        assertThat(access.isPanelEnabled("loggers")).isFalse();
        assertThat(access.panelDisabledReason("loggers"))
                .isEqualTo("Panel is disabled via bootui.panels.loggers.enabled=false");
    }

    @Test
    void theGlobalReadOnlySwitchWinsOverThePerPanelSetting() {
        MicronautPanelAccessConfig access = access(Map.of("bootui.read-only", "true"));

        assertThat(access.isPanelReadOnly("loggers")).isTrue();
        assertThat(access.panelReadOnlyReason("loggers")).isEqualTo("BootUI is read-only via bootui.read-only=true");
    }

    @Test
    void aPerPanelReadOnlySettingReportsItsOwnReason() {
        MicronautPanelAccessConfig access = access(Map.of("bootui.panels.loggers.read-only", "true"));

        assertThat(access.panelReadOnlyReason("loggers"))
                .isEqualTo("Panel is read-only via bootui.panels.loggers.read-only=true");
    }

    /**
     * Micronaut's own boolean conversion turns an unrecognized string into {@code false}, which would
     * silently widen access for a read-only switch, so BootUI parses these values strictly and falls back
     * to each key's own default instead.
     */
    @Test
    void failsClosedOnAnInvalidValue() {
        assertThat(access(Map.of("bootui.panels.loggers.enabled", "not-a-boolean"))
                        .isPanelEnabled("loggers"))
                .isTrue();
        assertThat(access(Map.of("bootui.read-only", "treu")).isGlobalReadOnly())
                .isFalse();
        assertThat(access(Map.of("bootui.panels.loggers.read-only", "yes")).isPanelReadOnly("loggers"))
                .isTrue();
    }

    private static MicronautPanelAccessConfig access(Map<String, Object> properties) {
        PropertyResolver config = new PropertySourcePropertyResolver(PropertySource.of("test", properties));
        return new MicronautPanelAccessConfig(config);
    }
}
