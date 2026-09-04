package io.github.jdubois.bootui.micronaut.health;

import io.github.jdubois.bootui.core.dto.HealthSetupStepDto;
import io.github.jdubois.bootui.engine.health.HealthGuidance;
import java.util.List;
import java.util.Set;

/**
 * The Micronaut-flavored setup guidance the shared engine {@code HealthService} renders when no health
 * backend is available — the analogue of the Quarkus adapter's {@code QuarkusHealthGuidance} (SmallRye
 * Health) and of the Spring adapter's Actuator guidance.
 *
 * <p>On Micronaut the backend is {@code micronaut-management}: without it the application has no health
 * indicators to aggregate, so the panel stays honest and tells the developer exactly which dependency and
 * which code to add rather than rendering an empty tree.
 */
public final class MicronautHealthGuidance {

    private static final String MANAGEMENT_UNAVAILABLE_REASON = "Micronaut Management health is not available";

    private static final List<HealthSetupStepDto> MANAGEMENT_SETUP = List.of(
            new HealthSetupStepDto(
                    "Add Micronaut Management",
                    "Add the micronaut-management dependency so Micronaut aggregates health indicators and BootUI"
                            + " can read the report in-process.",
                    List.of("io.micronaut:micronaut-management")),
            new HealthSetupStepDto(
                    "Add application health indicators",
                    "Implement HealthIndicator beans (or add libraries that contribute them, such as the JDBC"
                            + " datasource indicator) so the panel reflects real dependency health.",
                    List.of(
                            "class MyHealthIndicator implements io.micronaut.management.health.indicator.HealthIndicator")),
            new HealthSetupStepDto(
                    "Inspect the health endpoint when you need it",
                    "Micronaut serves /health once the endpoint is enabled; BootUI reads the same aggregated result"
                            + " in-process without an HTTP round trip.",
                    List.of("endpoints.health.enabled=true", "endpoints.health.details-visible=ANONYMOUS")));

    public static final HealthGuidance INSTANCE =
            new HealthGuidance(Set.of(), MANAGEMENT_UNAVAILABLE_REASON, MANAGEMENT_SETUP, null, List.of());

    private MicronautHealthGuidance() {}
}
