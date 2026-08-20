package io.github.jdubois.bootui.engine.support;

import java.time.Duration;

/**
 * Formats a {@link Duration} as the short, human-readable text BootUI shows for configured cache expiry and
 * similar policy values ({@code 750ms}, {@code 30s}, {@code 5m}, {@code 1h 30m}, {@code 2d}).
 *
 * <p>Deliberately lossless for the units it prints: a duration is rendered from its largest non-zero unit down
 * to milliseconds, so {@code PT1H30M} reads {@code 1h 30m} rather than being rounded to {@code 1h}.</p>
 */
public final class Durations {

    private Durations() {}

    /**
     * {@code null} for a {@code null} or negative duration, otherwise its short form.
     *
     * <p>A zero-length duration formats as {@code 0ms} rather than disappearing: providers such as Caffeine
     * treat a zero expiry as "expire immediately", and silently dropping it would render a cache that keeps
     * nothing as one with no expiry at all. Callers whose provider gives zero a different meaning (Spring
     * Data Redis reads a zero time-to-live as "never expires") must say so themselves before formatting.</p>
     */
    public static String format(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return null;
        }
        if (duration.isZero()) {
            return "0ms";
        }
        long days = duration.toDaysPart();
        int hours = duration.toHoursPart();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        int millis = duration.toMillisPart();

        StringBuilder text = new StringBuilder();
        append(text, days, "d");
        append(text, hours, "h");
        append(text, minutes, "m");
        append(text, seconds, "s");
        append(text, millis, "ms");
        return text.toString();
    }

    private static void append(StringBuilder text, long value, String unit) {
        if (value <= 0) {
            return;
        }
        if (!text.isEmpty()) {
            text.append(' ');
        }
        text.append(value).append(unit);
    }
}
