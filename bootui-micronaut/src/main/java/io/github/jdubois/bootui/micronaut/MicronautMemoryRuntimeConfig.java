package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.spi.HealthProbeManifest;
import io.github.jdubois.bootui.spi.MemoryRuntimeConfig;
import jakarta.inject.Singleton;

/**
 * Micronaut-specific runtime facts the Live Memory / JVM Tuning advisors need — the analogue of the
 * Quarkus adapter's {@code QuarkusMemoryRuntimeConfig}.
 *
 * <p>Two facts differ from Spring. Micronaut has no application-wide virtual-threads switch (virtual
 * threads are opted into per executor, for example
 * {@code micronaut.executors.blocking.type=virtual}), so there is no single property to cite and
 * {@link #virtualThreadsProperty()} returns {@code null}, which tells the JVM Tuning panel to omit the
 * app-wide advisory rather than recommend a property that does not exist. And the Kubernetes probe paths
 * are Micronaut Management's own, contributed only when that dependency is present.
 */
@RequiresBootUi
@Singleton
public class MicronautMemoryRuntimeConfig implements MemoryRuntimeConfig {

    private static final String HEALTH_INDICATOR_CLASS = "io.micronaut.management.health.indicator.HealthIndicator";

    private static final boolean MANAGEMENT_PRESENT = isManagementPresent();

    /**
     * Micronaut Management's health endpoint and its liveness/readiness sub-paths, the Micronaut analogue
     * of {@link HealthProbeManifest#SPRING_ACTUATOR}. Micronaut has no separate startup probe, so the
     * startup path points at the same liveness endpoint, exactly as the Spring manifest does.
     */
    static final HealthProbeManifest MICRONAUT_MANAGEMENT = new HealthProbeManifest(
            "/health/liveness",
            "/health/liveness",
            "/health/readiness",
            null,
            "Kubernetes health probes are omitted from the snippet; add verified startup, readiness, and liveness probes for the deployment.");

    @Override
    public boolean virtualThreadsEnabled() {
        return false;
    }

    @Override
    public String virtualThreadsProperty() {
        return null;
    }

    @Override
    public boolean kubernetesHealthProbesEnabled() {
        return MANAGEMENT_PRESENT;
    }

    @Override
    public HealthProbeManifest healthProbeManifest() {
        return MICRONAUT_MANAGEMENT;
    }

    private static boolean isManagementPresent() {
        try {
            Class.forName(HEALTH_INDICATOR_CLASS, false, MicronautMemoryRuntimeConfig.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
