package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.env.PropertySource;
import io.micronaut.context.env.PropertySourcePropertyResolver;
import io.micronaut.core.value.PropertyResolver;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pins path composition, which every filter and the shell's injected browser base depend on. */
class MicronautBootUiPathsTest {

    @Test
    void defaultsToTheReservedMounts() {
        PropertyResolver config = config(Map.of());

        assertThat(MicronautBootUiPaths.uiPath(config)).isEqualTo("/bootui");
        assertThat(MicronautBootUiPaths.apiPath(config)).isEqualTo("/bootui/api");
    }

    @Test
    void derivesTheApiMountFromTheUiMountWhenOnlyTheUiMountIsConfigured() {
        PropertyResolver config = config(Map.of("bootui.path", "/console"));

        assertThat(MicronautBootUiPaths.apiPath(config)).isEqualTo("/console/api");
    }

    @Test
    void prefixesEveryMountWithTheServerContextPath() {
        PropertyResolver config = config(Map.of("micronaut.server.context-path", "/app"));

        assertThat(MicronautBootUiPaths.applicationUiPath(config)).isEqualTo("/app/bootui");
        assertThat(MicronautBootUiPaths.applicationApiPath(config)).isEqualTo("/app/bootui/api");
    }

    @Test
    void rejectsAnInvalidMount() {
        assertThatThrownBy(() -> MicronautBootUiPaths.validate(config(Map.of("bootui.path", "relative"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsClosedToTheReservedMountsWhenConfigurationIsInvalid() {
        PropertyResolver config = config(Map.of("bootui.path", "relative"));

        assertThat(MicronautBootUiPaths.safeUiPath(config)).isEqualTo("/bootui");
        assertThat(MicronautBootUiPaths.safeApiPath(config)).isEqualTo("/bootui/api");
    }

    @Test
    void recognisesConsoleTrafficUnderEveryMountShape() {
        PropertyResolver config = config(Map.of(
                "bootui.path", "/console", "bootui.api-path", "/console/api", "micronaut.server.context-path", "/app"));

        assertThat(MicronautBootUiPaths.isBootUiRequest(config, "/app/console")).isTrue();
        assertThat(MicronautBootUiPaths.isBootUiRequest(config, "/app/console/api/beans"))
                .isTrue();
        assertThat(MicronautBootUiPaths.isBootUiRequest(config, "/app/bootui/assets/app.js"))
                .isTrue();
    }

    @Test
    void doesNotMistakeANeighbouringPathForConsoleTraffic() {
        PropertyResolver config = config(Map.of());

        assertThat(MicronautBootUiPaths.isBootUiRequest(config, "/bootui-other"))
                .isFalse();
        assertThat(MicronautBootUiPaths.isBootUiRequest(config, "/api/bootui-proxy"))
                .isFalse();
    }

    private static PropertyResolver config(Map<String, Object> properties) {
        return new PropertySourcePropertyResolver(PropertySource.of("test", properties));
    }
}
