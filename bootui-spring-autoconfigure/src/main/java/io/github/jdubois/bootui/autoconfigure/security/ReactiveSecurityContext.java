package io.github.jdubois.bootui.autoconfigure.security;

import io.github.jdubois.bootui.autoconfigure.config.BootUiActuatorDefaultsEnvironmentPostProcessor;
import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.CorsConfigModel;
import io.github.jdubois.bootui.autoconfigure.security.ReactiveSecurityModel.WebFilterChainModel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.DefaultPropertiesPropertySource;
import org.springframework.boot.env.RandomValuePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Read-only inputs handed to every Spring Security WebFlux Advisor rule: the introspected
 * {@code SecurityWebFilterChain}s and related security beans plus the application
 * {@link Environment}.
 */
record ReactiveSecurityContext(
        List<WebFilterChainModel> chains,
        List<CorsConfigModel> corsConfigs,
        boolean corsSourcePresent,
        List<String> reactiveJwtDecoderTypes,
        List<String> oauth2TokenValidatorTypes,
        List<String> opaqueTokenIntrospectorTypes,
        Environment environment) {

    ReactiveSecurityContext {
        chains = List.copyOf(chains);
        corsConfigs = List.copyOf(corsConfigs);
        reactiveJwtDecoderTypes = List.copyOf(reactiveJwtDecoderTypes);
        oauth2TokenValidatorTypes = List.copyOf(oauth2TokenValidatorTypes);
        opaqueTokenIntrospectorTypes = List.copyOf(opaqueTokenIntrospectorTypes);
    }

    boolean isTlsConfigured() {
        if (isGlobalTlsConfigured()) {
            return true;
        }
        return chains.stream().anyMatch(WebFilterChainModel::hasHttpsRedirectFilter);
    }

    private boolean isGlobalTlsConfigured() {
        if (isPropertyTrue("server.ssl.enabled")
                || firstProperty("server.ssl.key-store") != null
                || firstProperty("server.ssl.bundle") != null
                || firstProperty("server.ssl.certificate") != null) {
            return true;
        }
        String forwarded = firstProperty("server.forward-headers-strategy");
        return forwarded != null
                && ("framework".equalsIgnoreCase(forwarded) || "native".equalsIgnoreCase(forwarded));
    }

    static final List<String> SENSITIVE_ACTUATOR_ENDPOINTS =
            List.of("env", "beans", "configprops", "heapdump", "threaddump", "shutdown", "loggers", "mappings");

    private static Set<String> tokenize(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : commaSeparated.toLowerCase(Locale.ROOT).split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    Set<String> effectiveSensitiveActuatorExposure() {
        String include = firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null) {
            return Set.of();
        }
        String normalized = include.trim();
        Set<String> excluded = tokenize(firstHostProperty("management.endpoints.web.exposure.exclude"));
        boolean wildcardInclude = normalized.equals("*");
        if (wildcardInclude && excluded.isEmpty()) {
            return Set.of();
        }
        Set<String> included = wildcardInclude ? Set.of() : tokenize(normalized);
        Set<String> exposed = new LinkedHashSet<>();
        for (String sensitive : SENSITIVE_ACTUATOR_ENDPOINTS) {
            boolean isIncluded = wildcardInclude || included.contains(sensitive);
            if (isIncluded && !excluded.contains(sensitive)) {
                exposed.add(sensitive);
            }
        }
        return exposed;
    }

    boolean exposesBeyondHealthAndInfo() {
        String include = firstHostProperty("management.endpoints.web.exposure.include");
        if (include == null) {
            return false;
        }
        String normalized = include.toLowerCase(Locale.ROOT).trim();
        Set<String> excluded = tokenize(firstHostProperty("management.endpoints.web.exposure.exclude"));
        if (normalized.equals("*")) {
            if (excluded.isEmpty()) {
                return true;
            }
            return !excluded.containsAll(SENSITIVE_ACTUATOR_ENDPOINTS);
        }
        for (String token : normalized.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty() || trimmed.equals("health") || trimmed.equals("info")) {
                continue;
            }
            if (!excluded.contains(trimmed)) {
                return true;
            }
        }
        return false;
    }

    String firstProperty(String... keys) {
        for (String key : keys) {
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    boolean isPropertyTrue(String... keys) {
        String value = firstProperty(keys);
        return value != null && "true".equalsIgnoreCase(value);
    }

    String firstHostProperty(String... keys) {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return firstProperty(keys);
        }
        for (String key : keys) {
            String value = hostProperty(configurableEnvironment, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String hostProperty(ConfigurableEnvironment configurableEnvironment, String key) {
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
                continue;
            }
            Object value = propertySource.getProperty(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (text.isBlank()) {
                continue;
            }
            if (isBootUiActuatorDefault(propertySource, key, text)) {
                continue;
            }
            return text;
        }
        return null;
    }

    private boolean isBootUiActuatorDefault(PropertySource<?> propertySource, String key, String value) {
        return DefaultPropertiesPropertySource.NAME.equals(propertySource.getName())
                && BootUiActuatorDefaultsEnvironmentPostProcessor.isBootUiActuatorDefault(key, value);
    }

    String[] activeProfiles() {
        try {
            return environment.getActiveProfiles();
        } catch (RuntimeException ex) {
            return new String[0];
        }
    }

    boolean isProductionProfileActive() {
        for (String profile : activeProfiles()) {
            if (profile == null) {
                continue;
            }
            String normalized = profile.toLowerCase(Locale.ROOT);
            if (normalized.equals("prod")
                    || normalized.equals("production")
                    || normalized.equals("staging")
                    || normalized.startsWith("prod-")
                    || normalized.endsWith("-prod")
                    || normalized.endsWith("-production")) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern SUSPECTED_SECRET_KEY = Pattern.compile(
            ".*(password|passwd|secret|token|api-?key|client-secret|private-key).*", Pattern.CASE_INSENSITIVE);

    private static final Pattern NON_SECRET_VALUE_KEY_SUFFIX = Pattern.compile(
            ".*[.-](expiration|expiry|expires|ttl|timeout|duration|validity|max-age|maxage|refresh-interval)$",
            Pattern.CASE_INSENSITIVE);

    Set<String> suspectedHardcodedSecretKeys() {
        if (!(environment instanceof ConfigurableEnvironment configurableEnvironment)) {
            return Set.of();
        }
        Set<String> found = new LinkedHashSet<>();
        for (PropertySource<?> propertySource : configurableEnvironment.getPropertySources()) {
            if (!isScannableConfigSource(propertySource)) {
                continue;
            }
            if (!(propertySource instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (name == null
                        || name.isBlank()
                        || name.toLowerCase(Locale.ROOT).startsWith("bootui.")) {
                    continue;
                }
                if (!SUSPECTED_SECRET_KEY.matcher(name).matches()
                        || NON_SECRET_VALUE_KEY_SUFFIX.matcher(name).matches()) {
                    continue;
                }
                Object rawValue = propertySource.getProperty(name);
                if (!(rawValue instanceof String text) || text.isBlank() || text.contains("${")) {
                    continue;
                }
                found.add(name);
            }
        }
        return found;
    }

    private static boolean isScannableConfigSource(PropertySource<?> propertySource) {
        if (ConfigurationPropertySources.isAttachedConfigurationPropertySource(propertySource)) {
            return false;
        }
        String name = propertySource.getName();
        if (StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME.equals(name)
                || StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME.equals(name)
                || RandomValuePropertySource.RANDOM_PROPERTY_SOURCE_NAME.equals(name)
                || DefaultPropertiesPropertySource.NAME.equals(name)) {
            return false;
        }
        return !(propertySource instanceof ConfigTreePropertySource);
    }
}
