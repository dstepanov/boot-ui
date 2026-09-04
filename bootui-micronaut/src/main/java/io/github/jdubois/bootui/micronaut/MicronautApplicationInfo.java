package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.BootUiInfo;
import io.github.jdubois.bootui.core.dto.ActivationStatus;
import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Assembles the framework-neutral {@link OverviewDto} for the Micronaut adapter — the analogue of the
 * Spring adapter's {@code OverviewController} and of {@code QuarkusApplicationInfo}.
 *
 * <p>{@code GET /bootui/api/overview} is the shared shell's <em>chrome</em> data source (it powers the
 * header subtitle/status and primes the CSRF cookie). The Overview dashboard panel is available on
 * Micronaut, but its scoring dashboard is rendered entirely client-side from each advisor's own
 * endpoints, so this endpoint only needs to populate the shell-chrome fields ({@code applicationName},
 * {@code frameworkName}, {@code frameworkVersion}, {@code javaVersion}, {@code activeProfiles},
 * {@code activation}); the remaining panel-only fields are filled best-effort from the environment and
 * may be {@code null}.</p>
 *
 * <p>Micronaut environments are reported as {@code activeProfiles}: they are the same concept the shared
 * UI renders under that label on Spring (profiles) and Quarkus (SmallRye profiles).</p>
 */
@RequiresBootUi
@Singleton
public class MicronautApplicationInfo {

    private final Environment environment;
    private final MicronautServerPortSupplier serverPort;

    public MicronautApplicationInfo(Environment environment, MicronautServerPortSupplier serverPort) {
        this.environment = environment;
        this.serverPort = serverPort;
    }

    public OverviewDto overview() {
        return new OverviewDto(
                BootUiInfo.VERSION,
                applicationName(),
                "Micronaut",
                micronautVersion(),
                System.getProperty("java.version"),
                System.getProperty("java.vendor"),
                List.copyOf(environment.getActiveNames()),
                List.of(),
                // Micronaut serves HTTP on Netty and has no Spring servlet/reactive web-type distinction,
                // and the dashboard does not surface this field, so leave it unset.
                null,
                serverPort(),
                optInt("endpoints.all.port"),
                optString(MicronautContextPath.CONTEXT_PATH_KEY, ""),
                null,
                activation(),
                null);
    }

    private String applicationName() {
        String name = optString("micronaut.application.name", null);
        return (name == null || name.isBlank()) ? "application" : name;
    }

    /**
     * The running Micronaut version, read from the {@code micronaut-core} jar manifest through Micronaut's
     * own {@code VersionUtils}. Returns {@code null} when the version cannot be determined (for instance
     * from an exploded classpath with no manifest), which the UI renders as an unknown version rather than
     * a wrong one.
     */
    private String micronautVersion() {
        try {
            return io.micronaut.core.version.VersionUtils.getMicronautVersion();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Integer serverPort() {
        try {
            int port = serverPort.localServerPort();
            return port > 0 ? port : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private ActivationStatus activation() {
        BootUiMicronautActivation activation = BootUiMicronautActivationResolver.resolve(environment);
        // localhostOnly mirrors the Spring and Quarkus adapters: BootUiMicronautSafetyFilter enforces the
        // full shared LocalhostGuard policy (loopback-source trust, Host allow-list, and cross-site-write
        // rejection) over the whole /bootui surface, bypassed only when bootui.allow-non-localhost=true.
        boolean localhostOnly = !optBoolean(BootUiMicronautSafetyFilter.ALLOW_NON_LOCALHOST_KEY, false);
        return new ActivationStatus(activation.enabled(), localhostOnly, activation.reason(), activation.warnings());
    }

    private String optString(String key, String fallback) {
        try {
            return environment.getProperty(key, String.class).orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private Integer optInt(String key) {
        try {
            return environment.getProperty(key, Integer.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private boolean optBoolean(String key, boolean fallback) {
        try {
            return environment.getProperty(key, Boolean.class).orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
