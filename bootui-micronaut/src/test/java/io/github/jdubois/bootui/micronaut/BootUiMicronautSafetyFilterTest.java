package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the guard's scope and the "read live" contract of its list-valued policy keys.
 *
 * <p>Scope matters in both directions: too narrow and the console is reachable from anywhere; too wide and
 * the guard rejects an application's own routes as non-loopback.
 */
class BootUiMicronautSafetyFilterTest {

    @Test
    void guardsTheConfiguredMountsAndNothingElse() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("bootui.path", "/console"), "test")) {
            BootUiMicronautSafetyFilter filter = context.getBean(BootUiMicronautSafetyFilter.class);

            assertThat(filter.isBootUiRequest("/console")).isTrue();
            assertThat(filter.isBootUiRequest("/console/assets/app.js")).isTrue();
            assertThat(filter.isBootUiRequest("/console/api/beans")).isTrue();

            assertThat(filter.isBootUiRequest("/bootui")).isFalse();
            assertThat(filter.isBootUiRequest("/bootui/whatever")).isFalse();
            assertThat(filter.isBootUiRequest("/bootui-other")).isFalse();
            assertThat(filter.isBootUiRequest(null)).isFalse();
        }
    }

    /**
     * The CIDR and Host lists are parsed once per distinct raw value rather than once per request, so a
     * live configuration change must still take effect on the very next call.
     */
    @Test
    void reparsesTheTrustedRangesWhenTheirRawValueChanges() {
        try (ApplicationContext context = ApplicationContext.run("test")) {
            Environment environment = context.getEnvironment();
            BootUiMicronautSafetyFilter filter = context.getBean(BootUiMicronautSafetyFilter.class);

            assertThat(filter.isTrustedSource("10.1.2.3")).isFalse();

            environment.addPropertySource(PropertySource.of(
                    "trusted", Map.of(BootUiMicronautSafetyFilter.TRUSTED_PROXIES_KEY, "10.0.0.0/8")));
            assertThat(filter.isTrustedSource("10.1.2.3")).isTrue();

            environment.addPropertySource(PropertySource.of(
                    "trusted", Map.of(BootUiMicronautSafetyFilter.TRUSTED_PROXIES_KEY, "192.168.0.0/16")));
            assertThat(filter.isTrustedSource("10.1.2.3")).isFalse();
            assertThat(filter.isTrustedSource("192.168.4.5")).isTrue();
        }
    }
}
