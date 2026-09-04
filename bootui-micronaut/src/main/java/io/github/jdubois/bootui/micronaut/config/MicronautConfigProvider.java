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

    /** The value the panel reports: the resolved value, reduced to the JSON contract's own types. */
    private Object effectiveValue(String key) {
        return wireSafe(resolvedValue(key));
    }

    /** The value the resolver actually produced, used unmodified for source attribution. */
    private Object resolvedValue(String key) {
        try {
            return environment.getProperty(key, Object.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Reduces a resolved property value to the scalars, lists and maps the JSON contract is defined in.
     *
     * <p>{@code ConfigPropertyDto.value()} is typed {@code Object} because configuration genuinely holds
     * strings, numbers, booleans and structures. Micronaut's {@link Environment}, though, is a general bean
     * container: a programmatic property source can bind a value of any type at all — a {@link Class}, for
     * one, which is what a stock Micronaut context puts in the environment — and that object would then be
     * handed to whichever JSON stack the application chose. Jackson databind reflects over it and invents
     * some shape; Micronaut Serde refuses it outright ("No serializable introspection present"), which used
     * to fail the whole Configuration panel with a 500.
     *
     * <p>Normalizing here rather than at the DTO keeps {@code bootui-core} free of any JSON knowledge and
     * makes the panel's payload identical on both Micronaut JSON stacks and consistent with the Spring and
     * Quarkus adapters, which surface configuration as text. A {@link Class} renders as its binary name
     * (what Jackson databind already emitted), anything else unrecognized as its {@code toString()} — an
     * honest rendering rather than a hidden failure.
     */
    private static Object wireSafe(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Class<?> type) {
            return type.getName();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), wireSafe(v)));
            return copy;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> copy = new ArrayList<>();
            items.forEach(item -> copy.add(wireSafe(item)));
            return copy;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> copy = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(wireSafe(java.lang.reflect.Array.get(value, i)));
            }
            return copy;
        }
        return String.valueOf(value);
    }

    /**
     * The name of the property source that actually supplied {@code key}: the highest-precedence source
     * whose own value matches the effective one, falling back to the highest-precedence source that
     * declares the key at all when no value comparison succeeds (for instance for a structured value the
     * resolver rebuilt from several sources).
     */
    private String attribute(String key) {
        Object effective = resolvedValue(key);
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
