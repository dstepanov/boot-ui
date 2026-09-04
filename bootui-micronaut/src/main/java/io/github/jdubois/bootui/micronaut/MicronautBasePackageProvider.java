package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.spi.BasePackageProvider;
import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Supplies the application's base packages, which bound every ArchUnit-based advisor's bytecode import
 * (Architecture and REST API) so a scan never walks the whole classpath.
 *
 * <p>This is the Micronaut analogue of the Spring adapter binding to {@code AutoConfigurationPackages} and
 * of the Quarkus adapter reading the build-time Jandex-derived {@code bootui.internal.base-packages}
 * default. Micronaut deduces the application package itself while building the environment (the same
 * deduction that finds {@code @ConfigurationProperties} and configuration files) and exposes it through
 * {@link Environment#getPackages()}, so no build-time capture step is needed: the packages are read
 * <em>live</em> on every scan.
 *
 * <p>An operator can override the deduced set with {@code bootui.base-packages}, which accepts either a
 * YAML list or a comma-separated string. That is the escape hatch for a multi-module application whose
 * code does not all live under the deduced package, and it is also how a scan can be narrowed to keep it
 * fast.
 */
@RequiresBootUi
@Singleton
public class MicronautBasePackageProvider implements BasePackageProvider {

    public static final String BASE_PACKAGES_KEY = "bootui.base-packages";

    private final Environment environment;

    public MicronautBasePackageProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public List<String> basePackages() {
        List<String> configured = configuredPackages();
        if (!configured.isEmpty()) {
            return configured;
        }
        Set<String> deduced = new LinkedHashSet<>();
        for (String candidate : environment.getPackages()) {
            if (candidate != null && !candidate.isBlank()) {
                deduced.add(candidate.trim());
            }
        }
        return List.copyOf(deduced);
    }

    private List<String> configuredPackages() {
        List<String> values = environment
                .getProperty(BASE_PACKAGES_KEY, Argument.listOf(String.class))
                .orElse(null);
        if (values != null && !values.isEmpty()) {
            return normalize(values);
        }
        return environment
                .getProperty(BASE_PACKAGES_KEY, String.class)
                .map(raw -> normalize(List.of(raw.split(","))))
                .orElseGet(List::of);
    }

    private static List<String> normalize(List<String> raw) {
        List<String> packages = new ArrayList<>();
        for (String value : raw) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                packages.add(trimmed);
            }
        }
        return List.copyOf(packages);
    }
}
