package io.github.jdubois.bootui.engine.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the short expiry phrases the Cache panel shows for a tier ({@code expire after write 5m},
 * {@code time to live 10m}, {@code no expiry}).
 *
 * <p>The wording lives here, in the framework-neutral engine, rather than in each adapter: the Cache panel is
 * one shared contract across Spring MVC, Spring WebFlux and Quarkus, so the same configured policy has to read
 * identically on every stack. Adapters hand over the {@link Duration} their provider exposes and this class
 * decides how it is said.</p>
 */
public final class CacheExpiryText {

    /** The phrase for a tier that keeps entries until they are evicted or cleared. */
    public static final String NO_EXPIRY = "no expiry";

    private CacheExpiryText() {}

    /** {@code expire after write <duration>}, or {@code null} when the duration is absent or negative. */
    public static String expireAfterWrite(Duration duration) {
        return phrase("expire after write", duration);
    }

    /** {@code expire after access <duration>}, or {@code null} when the duration is absent or negative. */
    public static String expireAfterAccess(Duration duration) {
        return phrase("expire after access", duration);
    }

    /** {@code refresh after write <duration>}, or {@code null} when the duration is absent or negative. */
    public static String refreshAfterWrite(Duration duration) {
        return phrase("refresh after write", duration);
    }

    /** The phrase for a provider that computes an expiry separately for every entry. */
    public static String expirePerEntry() {
        return "expire per entry";
    }

    /**
     * {@code time to live <duration>} or {@code time to idle <duration>}, and {@link #NO_EXPIRY} when the
     * duration is zero, absent or negative — Spring Data Redis reads a zero time-to-live as "never expires".
     */
    public static String timeToLive(Duration duration, boolean idle) {
        String text = duration == null || duration.isZero() ? null : Durations.format(duration);
        return text == null ? NO_EXPIRY : label(idle) + " " + text;
    }

    /** The phrase for a Redis configuration whose time-to-live is a function of the cached entry. */
    public static String timeToLiveComputedPerEntry(boolean idle) {
        return label(idle) + " computed per entry";
    }

    /**
     * {@code <label> <rawValue>} for a configured value the runtime could not convert to a {@link Duration}.
     * Showing the application's own text is more honest than dropping a policy that is genuinely configured.
     */
    public static String verbatim(String label, String rawValue) {
        if (label == null || rawValue == null || rawValue.isBlank()) {
            return null;
        }
        return label + " " + rawValue.trim();
    }

    /** Joins the non-null phrases of one tier into the single summary the panel renders, or {@code null}. */
    public static String summary(String... phrases) {
        if (phrases == null) {
            return null;
        }
        List<String> present = new ArrayList<>(phrases.length);
        for (String phrase : phrases) {
            if (phrase != null && !phrase.isBlank()) {
                present.add(phrase);
            }
        }
        return present.isEmpty() ? null : String.join(", ", present);
    }

    private static String phrase(String label, Duration duration) {
        String text = Durations.format(duration);
        return text == null ? null : label + " " + text;
    }

    private static String label(boolean idle) {
        return idle ? "time to idle" : "time to live";
    }
}
