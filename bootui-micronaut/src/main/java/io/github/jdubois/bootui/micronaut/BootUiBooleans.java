package io.github.jdubois.bootui.micronaut;

import io.micronaut.core.value.PropertyResolver;
import java.util.Locale;
import org.slf4j.Logger;

/**
 * Strict boolean reading for BootUI's safety-relevant switches.
 *
 * <p>Micronaut's own conversion turns an unrecognized string into {@code false} rather than reporting it,
 * which is the wrong direction for a switch whose safe value is {@code true}: a typo in
 * {@code bootui.mask-secrets} or {@code bootui.read-only} would silently <em>widen</em> access. BootUI's
 * contract on every adapter is that a missing or invalid value falls back to the key's own default, so this
 * helper parses the raw string itself, accepts the spellings YAML and the command line produce, and warns
 * about anything else before failing closed.
 */
final class BootUiBooleans {

    private BootUiBooleans() {}

    /**
     * Reads {@code key} as a boolean, returning {@code defaultValue} for a missing, blank or unrecognized
     * value. An unrecognized value is warned about once per read so a typo is visible in the log rather
     * than only in surprising behavior.
     */
    static boolean value(PropertyResolver config, String key, boolean defaultValue, Logger log) {
        String raw;
        try {
            raw = config.getProperty(key, String.class).orElse(null);
        } catch (RuntimeException ex) {
            log.warn("Ignoring unreadable BootUI property '{}'; falling back to {}.", key, defaultValue);
            return defaultValue;
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> {
                log.warn("Ignoring invalid BootUI property '{}={}'; falling back to {}.", key, raw, defaultValue);
                yield defaultValue;
            }
        };
    }
}
