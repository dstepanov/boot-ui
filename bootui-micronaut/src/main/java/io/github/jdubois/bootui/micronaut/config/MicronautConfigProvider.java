package io.github.jdubois.bootui.micronaut.config;

import io.github.jdubois.bootui.core.dto.ConfigPropertySuggestionDto;
import io.github.jdubois.bootui.spi.ConfigEntry;
import io.github.jdubois.bootui.spi.ConfigProvider;
import io.github.jdubois.bootui.spi.ProfileSource;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Micronaut {@link ConfigProvider} backed by the {@link Environment}'s property sources.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code SpringConfigProvider} and of the Quarkus
 * adapter's {@code QuarkusConfigProvider}: it enumerates the effective configuration so the shared engine
 * {@code ConfigService} can mask, sort, filter, page and group it.
 *
 * <p>Each key is reported once, with the value the application actually sees
 * ({@link Environment#getProperty}) and the name of the property source that supplied it. Attribution
 * prefers the source whose own value matches the effective one and, when several match, the
 * highest-precedence (lowest {@link PropertySource#getOrder() order}) of them — so a key set in both
 * {@code application.yml} and a system property is attributed to the system property that actually won.
 * Reading through the resolver rather than the raw source map also means a value that cannot be resolved
 * degrades to {@code null} instead of failing the enumeration, and no placeholder expansion is performed
 * here that could leak a secret past {@code SecretMasker}.
 *
 * <p>Micronaut's environment-specific configuration files ({@code application-dev.yml} and friends) are
 * separate, named property sources, so they map directly onto {@link #profileSources()} — giving the
 * Profile Diff panel real content on Micronaut.
 *
 * <p>Two concerns are intentionally degraded versus Spring, exactly as on Quarkus: there is no
 * runtime-overrides source ({@link #overrideSourceName()} is {@code null}, so the panel is read-only) and
 * there is no {@code spring-configuration-metadata.json} equivalent to read, so {@link #suggestions()} is
 * empty.
 */
public final class MicronautConfigProvider implements ConfigProvider {

    private final Environment environment;

    public MicronautConfigProvider(Environment environment) {
        this.environment = environment;
    }

    @Override
    public List<String> activeProfiles() {
        return List.copyOf(environment.getActiveNames());
    }

    @Override
    public List<String> sources() {
        return orderedSources().stream().map(PropertySource::getName).toList();
    }

    @Override
    public List<ConfigEntry> entries() {
        Map<String, ConfigEntry> merged = new LinkedHashMap<>();
        for (PropertySource source : orderedSources()) {
            for (String key : source) {
                if (key == null || key.isBlank() || merged.containsKey(key)) {
                    continue;
                }
                merged.put(key, new ConfigEntry(key, effectiveValue(key), attribute(key)));
            }
        }
        return new ArrayList<>(merged.values());
    }

    @Override
    public String overrideSourceName() {
        return null;
    }

    @Override
    public List<ProfileSource> profileSources() {
        List<String> activeProfiles = activeProfiles();
        if (activeProfiles.isEmpty()) {
            return List.of();
        }
        List<ProfileSource> profileSources = new ArrayList<>();
        for (PropertySource source : orderedSources()) {
            String profile = profileOf(source.getName(), activeProfiles);
            if (profile == null) {
                continue;
            }
            List<ConfigEntry> entries = new ArrayList<>();
            for (String key : source) {
                if (key != null && !key.isBlank()) {
                    entries.add(new ConfigEntry(key, source.get(key), source.getName()));
                }
            }
            if (!entries.isEmpty()) {
                profileSources.add(new ProfileSource(source.getName(), profile, entries));
            }
        }
        return profileSources;
    }

    @Override
    public List<ConfigPropertySuggestionDto> suggestions() {
        return List.of();
    }

    /**
     * The active environment a property source belongs to, or {@code null} for a source that is not
     * environment-specific. Micronaut names these sources {@code <config>-<environment>} (for example
     * {@code application-dev}), which is the only runtime signal that a source is profile-scoped.
     */
    private static String profileOf(String sourceName, List<String> activeProfiles) {
        if (sourceName == null) {
            return null;
        }
        for (String profile : activeProfiles) {
            if (sourceName.endsWith("-" + profile)) {
                return profile;
            }
        }
        return null;
    }

    private Object effectiveValue(String key) {
        try {
            return environment.getProperty(key, Object.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * The name of the property source that actually supplied {@code key}: the highest-precedence source
     * whose own value matches the effective one, falling back to the highest-precedence source that
     * declares the key at all when no value comparison succeeds (for instance for a structured value the
     * resolver rebuilt from several sources).
     */
    private String attribute(String key) {
        Object effective = effectiveValue(key);
        String declaring = null;
        for (PropertySource source : orderedSources()) {
            Object value = source.get(key);
            if (value == null) {
                continue;
            }
            if (declaring == null) {
                declaring = source.getName();
            }
            if (Objects.equals(value, effective) || String.valueOf(value).equals(String.valueOf(effective))) {
                return source.getName();
            }
        }
        return declaring;
    }

    /**
     * The environment's property sources in precedence order, highest first. Micronaut orders property
     * sources ascending by {@link PropertySource#getOrder()} with the lowest value winning (system
     * properties are {@code -100}, environment variables {@code -200}, ordinary configuration files
     * {@code 0}), so ascending order is precedence order.
     */
    private List<PropertySource> orderedSources() {
        List<PropertySource> sources = new ArrayList<>(environment.getPropertySources());
        sources.sort(Comparator.comparingInt(PropertySource::getOrder));
        return sources;
    }
}
