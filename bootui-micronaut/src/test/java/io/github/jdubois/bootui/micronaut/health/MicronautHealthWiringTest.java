package io.github.jdubois.bootui.micronaut.health;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.micronaut.context.ApplicationContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.HealthResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins that the Health panel is actually <em>wired</em>, not merely compiled, and that the aggregated result
 * is mapped onto the neutral tree with each component's own status.
 *
 * <p>{@link MicronautHealthProvider} implements the neutral SPI but is not itself a bean, so nothing
 * discovers it: it has to be built explicitly by {@code BootUiEngineFactory}. When that wiring is missing
 * the engine still answers 200 — with a DISABLED root and "no health backend" guidance — so a
 * status-code-only test cannot tell the panel is dead. This test looks at the payload instead.
 *
 * <p>The wiring assertions alone were not enough, which is how the {@code UNKNOWN}-component bug shipped:
 * they only inspected the <em>root</em> node, and a root whose every child is {@code UNKNOWN} still has a
 * non-blank status and a non-empty components list. The mapping tests below therefore assert on the
 * components themselves — status and details — against a hand-built {@link HealthResult} tree shaped exactly
 * as {@code DefaultHealthAggregator} shapes one: nested {@link HealthResult} objects keyed by indicator name,
 * not maps.
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
            assertThat(health.components())
                    .as("every aggregated indicator must carry its own status, never the UNKNOWN fallback")
                    .allSatisfy(
                            component -> assertThat(component.status()).isNotEqualTo(HealthStatus.UNKNOWN.getName()));
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

    /**
     * The bug this mapping fix addresses: a nested {@link HealthResult} must map to a component with the
     * indicator's own status and the indicator's own details map only — not to an {@code UNKNOWN} component
     * whose details are the whole result object. The nested result's {@code name} is deliberately the
     * application name here, as the aggregator sets it; the component name must come from the map key.
     */
    @Test
    void mapsANestedUpIndicatorToItsOwnStatusAndDetails() {
        HealthResult aggregated = aggregate(
                HealthStatus.UP,
                Map.of("diskSpace", indicator(HealthStatus.UP, Map.of("total", 500L, "free", 250L, "threshold", 10L))));

        HealthNodeDto root = MicronautHealthProvider.map(aggregated);

        assertThat(root.name()).isEqualTo("application");
        assertThat(root.status()).isEqualTo("UP");
        assertThat(root.details()).isNull();
        assertThat(root.components()).singleElement().satisfies(component -> {
            assertThat(component.name()).isEqualTo("diskSpace");
            assertThat(component.status()).isEqualTo("UP");
            assertThat(component.details()).isEqualTo(Map.of("total", 500L, "free", 250L, "threshold", 10L));
            assertThat(component.components()).isEmpty();
        });
    }

    /** A failing indicator must surface as DOWN, and its error details must survive the mapping. */
    @Test
    void mapsANestedDownIndicatorToDown() {
        HealthResult aggregated = aggregate(
                HealthStatus.DOWN,
                Map.of(
                        "jdbc",
                        indicator(HealthStatus.DOWN, Map.of("error", "java.sql.SQLException: connection refused"))));

        HealthNodeDto root = MicronautHealthProvider.map(aggregated);

        assertThat(root.status()).isEqualTo("DOWN");
        assertThat(root.components()).singleElement().satisfies(component -> {
            assertThat(component.name()).isEqualTo("jdbc");
            assertThat(component.status()).isEqualTo("DOWN");
            assertThat(component.details()).isEqualTo(Map.of("error", "java.sql.SQLException: connection refused"));
        });
    }

    /**
     * A composite indicator aggregates further results into its own details, so those become the component's
     * children rather than its details table.
     */
    @Test
    void mapsACompositeIndicatorsNestedResultsToChildComponents() {
        HealthResult aggregated = aggregate(
                HealthStatus.UP,
                Map.of(
                        "compositeDiscoveryClient()",
                        indicator(HealthStatus.UP, Map.of("consul", indicator(HealthStatus.UP, Map.of("nodes", 3))))));

        HealthNodeDto root = MicronautHealthProvider.map(aggregated);

        assertThat(root.components()).singleElement().satisfies(composite -> {
            assertThat(composite.name()).isEqualTo("compositeDiscoveryClient()");
            assertThat(composite.status()).isEqualTo("UP");
            assertThat(composite.details()).isNull();
            assertThat(composite.components()).singleElement().satisfies(child -> {
                assertThat(child.name()).isEqualTo("consul");
                assertThat(child.status()).isEqualTo("UP");
                assertThat(child.details()).isEqualTo(Map.of("nodes", 3));
            });
        });
    }

    /**
     * An indicator that reports a plain {@code {status, details}} map instead of a {@link HealthResult} is
     * still mapped on its own status, and a value that is neither shape stays visible as details under
     * {@code UNKNOWN} rather than being dropped.
     */
    @Test
    void keepsThePlainMapAndScalarShapes() {
        Map<String, Object> children = new LinkedHashMap<>();
        children.put("custom", Map.of("status", "UP", "details", Map.of("note", "hand-rolled")));
        children.put("odd", "not-a-health-result");

        HealthNodeDto root = MicronautHealthProvider.map(aggregate(HealthStatus.UP, children));

        assertThat(root.components())
                .satisfiesExactly(
                        custom -> {
                            assertThat(custom.name()).isEqualTo("custom");
                            assertThat(custom.status()).isEqualTo("UP");
                            assertThat(custom.details()).isEqualTo(Map.of("note", "hand-rolled"));
                        },
                        odd -> {
                            assertThat(odd.name()).isEqualTo("odd");
                            assertThat(odd.status()).isEqualTo("UNKNOWN");
                            assertThat(odd.details()).isEqualTo("not-a-health-result");
                        });
    }

    /**
     * Builds the composite result {@code DefaultHealthAggregator} produces: one result whose details map is
     * the per-indicator index.
     */
    private static HealthResult aggregate(HealthStatus status, Map<String, Object> indicators) {
        return HealthResult.builder(null, status).details(indicators).build();
    }

    /**
     * Builds a nested indicator result the way the aggregator does — with the <em>application</em> name, not
     * the indicator's, so a mapping that read the name from the result instead of the map key would fail.
     */
    private static HealthResult indicator(HealthStatus status, Object details) {
        return HealthResult.builder("test-application", status).details(details).build();
    }
}
