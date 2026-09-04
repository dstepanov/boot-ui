package io.github.jdubois.bootui.micronaut.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins that the Health panel is actually <em>wired</em>, not merely compiled.
 *
 * <p>{@link MicronautHealthProvider} implements the neutral SPI but is not itself a bean, so nothing
 * discovers it: it has to be built explicitly by {@code BootUiEngineFactory}. When that wiring is missing
 * the engine still answers 200 — with a DISABLED root and "no health backend" guidance — so a
 * status-code-only test cannot tell the panel is dead. This test looks at the payload instead.
 */
class MicronautHealthWiringTest {

    @Test
    void aggregatesTheApplicationsOwnIndicatorsWhenManagementIsPresent() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("endpoints.health.enabled", true), "test")) {
            HealthNodeDto health = context.getBean(HealthService.class).health();

            assertThat(health.available())
                    .as("micronaut-management is on the classpath, so the panel must have a backend")
                    .isTrue();
            assertThat(health.unavailableReason()).isNull();
            // The status itself depends on which indicators this test classpath happens to publish; what
            // this test pins is that they were aggregated at all.
            assertThat(health.status()).isNotBlank();
            assertThat(health.components()).isNotEmpty();
        }
    }

    /**
     * The honest fallback: with the health endpoint switched off, micronaut-management publishes no
     * aggregator, and the panel says so with setup guidance rather than showing an empty tree.
     */
    @Test
    void reportsThePanelUnavailableWhenTheHealthEndpointIsDisabled() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("endpoints.health.enabled", false), "test")) {
            HealthNodeDto health = context.getBean(HealthService.class).health();

            assertThat(health.available()).isFalse();
            assertThat(health.unavailableReason()).isNotBlank();
            assertThat(health.setup()).isNotEmpty();
        }
    }
}
