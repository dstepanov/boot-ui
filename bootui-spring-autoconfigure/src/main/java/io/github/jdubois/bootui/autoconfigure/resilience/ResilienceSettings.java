package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicySettingDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Small builder for the ordered {@code settings} list on a policy, plus the shared rendering rules for
 * durations, percentages and counts.
 *
 * <p>It imports no resilience library type, so it is safe to load from an always-loaded class. Provenance
 * is derived by comparing the effective value with the library's documented default: equal means
 * {@code DEFAULT}, different means {@code CONFIGURED}. A value BootUI cannot compare is reported as
 * {@code UNKNOWN} rather than guessed.</p>
 */
final class ResilienceSettings {

    private final List<ResiliencePolicySettingDto> settings = new ArrayList<>();

    /** Adds a setting whose provenance is derived by comparing {@code value} with {@code defaultValue}. */
    ResilienceSettings add(String name, Object value, Object defaultValue) {
        return addRendered(name, render(value), provenance(value, defaultValue));
    }

    /**
     * Adds a percentage setting, keeping the unit visible while still deriving provenance from the raw
     * numeric comparison.
     */
    ResilienceSettings addPercent(String name, float value, float defaultValue) {
        return addRendered(name, percent(value), provenance(value, defaultValue));
    }

    /** Adds a setting whose provenance BootUI cannot determine. */
    ResilienceSettings addUnknownProvenance(String name, Object value) {
        return addRendered(name, render(value), ResilienceVocabulary.PROVENANCE_UNKNOWN);
    }

    /** Adds a setting that is explicitly configured (an annotation attribute the user wrote). */
    ResilienceSettings addConfigured(String name, Object value) {
        return addRendered(name, render(value), ResilienceVocabulary.PROVENANCE_CONFIGURED);
    }

    private ResilienceSettings addRendered(String name, String value, String provenance) {
        if (value != null) {
            settings.add(new ResiliencePolicySettingDto(name, value, provenance));
        }
        return this;
    }

    List<ResiliencePolicySettingDto> build() {
        return List.copyOf(settings);
    }

    private static String provenance(Object value, Object defaultValue) {
        if (defaultValue == null) {
            return ResilienceVocabulary.PROVENANCE_UNKNOWN;
        }
        return java.util.Objects.equals(String.valueOf(value), String.valueOf(defaultValue))
                ? ResilienceVocabulary.PROVENANCE_DEFAULT
                : ResilienceVocabulary.PROVENANCE_CONFIGURED;
    }

    /**
     * Renders a value for display. Durations become an explicit millisecond count, floats keep one decimal,
     * and {@code null} is dropped entirely so an unavailable value is omitted rather than shown as "null".
     *
     * <p>A duration shorter than a millisecond keeps a finer unit: Resilience4j's default rate limiter
     * refresh period is 500 nanoseconds, and rounding it to {@code 0 ms} would tell the developer the
     * limiter refreshes instantly.</p>
     */
    static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Duration duration) {
            return renderDuration(duration);
        }
        if (value instanceof Float floatValue) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", floatValue));
        }
        if (value instanceof Double doubleValue) {
            return trimTrailingZero(String.format(java.util.Locale.ROOT, "%.1f", doubleValue));
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private static String renderDuration(Duration duration) {
        int nanos = duration.getNano();
        if (duration.getSeconds() == 0 && nanos != 0 && nanos < 1_000_000) {
            return nanos < 1_000 ? nanos + " ns" : nanos / 1_000 + " \u00b5s";
        }
        return duration.toMillis() + " ms";
    }

    private static String trimTrailingZero(String value) {
        return value.endsWith(".0") ? value.substring(0, value.length() - 2) : value;
    }

    /** Renders a percentage threshold, keeping the unit visible in the UI. */
    static String percent(float value) {
        return render(value) + "%";
    }
}
