package io.github.jdubois.bootui.micronaut;

import io.micronaut.context.env.Environment;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether the BootUI console is wired into a Micronaut application, at behavioral parity with the
 * Spring adapter's {@code BootUiActivationCondition}.
 *
 * <p>Micronaut environments are the analogue of Spring profiles, so the same three-state
 * {@code bootui.enabled} switch ({@code AUTO}/{@code ON}/{@code OFF}) is evaluated against the active
 * environment names:</p>
 *
 * <ul>
 *   <li>an active environment listed in {@code bootui.disabled-environments} (default {@code prod},
 *       {@code production}) disables the console, unless {@code bootui.enabled=ON} forces it on — which is
 *       reported back as a warning, exactly as on Spring;</li>
 *   <li>{@code bootui.enabled=OFF} disables it, {@code bootui.enabled=ON} enables it;</li>
 *   <li>otherwise ({@code AUTO}, the default) an active environment listed in
 *       {@code bootui.enabled-environments} enables it. The default list is {@code dev}, {@code local} and
 *       {@code test}: {@code dev} and {@code local} match the Spring adapter's defaults, and {@code test}
 *       is added because Micronaut deduces the {@code test} environment automatically when the application
 *       is started from a JUnit/Spock test — the analogue of Quarkus' {@code LaunchMode.TEST}, where the
 *       console is wired so the shared conformance suite can run against it.</li>
 * </ul>
 *
 * <p>Everything else <em>fails closed</em>: an unknown {@code bootui.enabled} value, or no matching
 * environment, leaves the console dark. Micronaut does not deduce a {@code dev} environment on its own
 * (unlike {@code test}), so a plain {@code java -jar} run is dark by default and an operator opts in with
 * {@code micronaut.environments=dev} or {@code bootui.enabled=ON} — the fail-closed direction.</p>
 */
public final class BootUiMicronautActivationResolver {

    static final String ENABLED_KEY = "bootui.enabled";
    static final String ENABLED_ENVIRONMENTS_KEY = "bootui.enabled-environments";
    static final String DISABLED_ENVIRONMENTS_KEY = "bootui.disabled-environments";

    /** Mirrors the Spring adapter's {@code BootUiDefaults.ENABLED_PROFILES}, plus Micronaut's deduced {@code test}. */
    static final List<String> DEFAULT_ENABLED_ENVIRONMENTS = List.of("dev", "local", Environment.TEST);

    /** Mirrors the Spring adapter's {@code BootUiDefaults.DISABLED_PROFILES}. */
    static final List<String> DEFAULT_DISABLED_ENVIRONMENTS = List.of("prod", "production");

    private BootUiMicronautActivationResolver() {}

    public static BootUiMicronautActivation resolve(Environment environment) {
        return resolve(environment, environment.getActiveNames());
    }

    static BootUiMicronautActivation resolve(
            io.micronaut.core.value.PropertyResolver config, Collection<String> activeEnvironments) {
        String rawMode =
                config.getProperty(ENABLED_KEY, String.class).orElse("AUTO").trim();
        String mode = normalizeMode(rawMode);
        List<String> warnings = new ArrayList<>();

        if (!List.of("AUTO", "ON", "OFF").contains(mode)) {
            return new BootUiMicronautActivation(
                    false, "Disabled: invalid " + ENABLED_KEY + " value '" + rawMode + "'", warnings);
        }

        for (String environment : listProperty(config, DISABLED_ENVIRONMENTS_KEY, DEFAULT_DISABLED_ENVIRONMENTS)) {
            if (activeEnvironments.contains(environment)) {
                if ("ON".equals(mode)) {
                    warnings.add("Environment '" + environment + "' is in " + DISABLED_ENVIRONMENTS_KEY + " but "
                            + ENABLED_KEY + "=ON forces it on.");
                    return new BootUiMicronautActivation(
                            true,
                            "Explicitly enabled (" + ENABLED_KEY + "=ON) despite disabled environment '" + environment
                                    + "'",
                            warnings);
                }
                return new BootUiMicronautActivation(
                        false,
                        "Disabled because active environment '" + environment + "' is in " + DISABLED_ENVIRONMENTS_KEY,
                        warnings);
            }
        }

        if ("OFF".equals(mode)) {
            return new BootUiMicronautActivation(false, "Disabled by " + ENABLED_KEY + "=OFF", warnings);
        }
        if ("ON".equals(mode)) {
            return new BootUiMicronautActivation(true, "Enabled by " + ENABLED_KEY + "=ON", warnings);
        }

        for (String environment : listProperty(config, ENABLED_ENVIRONMENTS_KEY, DEFAULT_ENABLED_ENVIRONMENTS)) {
            if (activeEnvironments.contains(environment)) {
                return new BootUiMicronautActivation(
                        true, "Enabled by active environment '" + environment + "'", warnings);
            }
        }

        return new BootUiMicronautActivation(
                false, "Disabled: no active environment is listed in " + ENABLED_ENVIRONMENTS_KEY, warnings);
    }

    /**
     * Normalizes a configured {@code bootui.enabled} value to a canonical {@code AUTO}/{@code ON}/
     * {@code OFF} token, mapping the boolean-ish values YAML parses ({@code on}/{@code off},
     * {@code true}/{@code false}, {@code yes}/{@code no}) exactly as the Spring adapter does. Genuinely
     * unknown values are passed through unchanged so they still fail closed.
     */
    static String normalizeMode(String rawMode) {
        String mode = rawMode.toUpperCase(Locale.ROOT);
        return switch (mode) {
            case "ON", "TRUE", "YES" -> "ON";
            case "OFF", "FALSE", "NO" -> "OFF";
            default -> mode;
        };
    }

    private static List<String> listProperty(
            io.micronaut.core.value.PropertyResolver config, String key, List<String> defaults) {
        List<String> values = config.getProperty(key, io.micronaut.core.type.Argument.listOf(String.class))
                .orElse(null);
        if (values != null && !values.isEmpty()) {
            return values.stream()
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList();
        }
        String raw = config.getProperty(key, String.class).orElse(null);
        if (raw == null || raw.isBlank()) {
            return defaults;
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    static Set<String> defaultEnabledEnvironments() {
        return Set.copyOf(DEFAULT_ENABLED_ENVIRONMENTS);
    }
}
