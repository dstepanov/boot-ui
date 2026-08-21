package io.github.jdubois.bootui.engine.cache;

import io.github.jdubois.bootui.core.dto.CacheStatisticsDto;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;

/**
 * Turns an adapter's raw {@link CacheStatisticsSnapshot} into the browser-facing {@link CacheStatisticsDto},
 * owning every presentation rule so both adapters behave identically.
 *
 * <p>Three rules matter for truthfulness:</p>
 *
 * <ul>
 *   <li>An impossible counter (negative, {@code NaN} or infinite — which a provider can produce after an
 *       overflow or a concurrent reset) is dropped rather than rendered.</li>
 *   <li>Statistics are only reported as available when the provider says it is recording <em>and</em> at least
 *       one counter survived that check.</li>
 *   <li>A hit ratio is derived only from a hit and a miss counter that the adapter declared comparable — same
 *       counter family, same scope, same window — and only when their sum is positive. Every other case leaves
 *       both ratios {@code null} with a reason, so an idle cache never renders as "0% hit ratio" and counters
 *       from different windows are never blended.</li>
 * </ul>
 */
final class CacheStatisticsAssembler {

    /** Counters read from the cache provider's own statistics API. */
    static final String SOURCE_NATIVE = "NATIVE";

    /** No statistics are available for this scope. */
    static final String SOURCE_NONE = "NONE";

    /** Counters describe a whole cache. */
    static final String SCOPE_CACHE = "CACHE";

    /** Counters describe one backing tier of a cache. */
    static final String SCOPE_TIER = "TIER";

    private CacheStatisticsAssembler() {}

    static CacheStatisticsDto toDto(CacheStatisticsSnapshot snapshot, String scope) {
        if (snapshot == null) {
            return unavailable(null, scope, "No native statistics API is exposed here.");
        }
        if (!snapshot.available()) {
            return unavailable(
                    snapshot.provider(),
                    scope,
                    reasonOr(snapshot.unavailableReason(), "This cache provider is not recording statistics."));
        }

        Double requests = sanitize(snapshot.requests());
        Double hits = sanitize(snapshot.hits());
        Double misses = sanitize(snapshot.misses());
        Double puts = sanitize(snapshot.puts());
        Double evictions = sanitize(snapshot.evictions());
        Double removals = sanitize(snapshot.removals());
        Double loadSuccesses = sanitize(snapshot.loadSuccesses());
        Double loadFailures = sanitize(snapshot.loadFailures());
        Double size = sanitize(snapshot.size());

        boolean anyCounter = requests != null
                || hits != null
                || misses != null
                || puts != null
                || evictions != null
                || removals != null
                || loadSuccesses != null
                || loadFailures != null
                || size != null;
        if (!anyCounter) {
            return unavailable(
                    snapshot.provider(), scope, "This cache provider reported no usable statistics counters.");
        }

        Ratios ratios = ratios(snapshot.countersComparable(), hits, misses);
        return new CacheStatisticsDto(
                true,
                SOURCE_NATIVE,
                snapshot.provider(),
                scope,
                snapshot.window() == null ? CacheStatisticsSnapshot.WINDOW_UNKNOWN : snapshot.window(),
                snapshot.since(),
                null,
                requests != null ? requests : ratios.total,
                hits,
                misses,
                ratios.hitRatio,
                ratios.missRatio,
                puts,
                evictions,
                removals,
                loadSuccesses,
                loadFailures,
                size,
                ratios.reason);
    }

    static CacheStatisticsDto unavailable(String provider, String scope, String reason) {
        return new CacheStatisticsDto(
                false,
                SOURCE_NONE,
                provider,
                scope,
                CacheStatisticsSnapshot.WINDOW_UNKNOWN,
                null,
                reasonOr(reason, "No native statistics API is exposed here."),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "No hit and miss counters are available to derive a ratio from.");
    }

    private static Ratios ratios(boolean comparable, Double hits, Double misses) {
        if (hits == null || misses == null) {
            return Ratios.unavailable(
                    null, "This cache provider does not expose both a hit and a miss counter for this scope.");
        }
        if (!comparable) {
            return Ratios.unavailable(
                    null,
                    "The hit and miss counters are not recorded over a comparable window, so a ratio would be"
                            + " misleading.");
        }
        double total = hits + misses;
        if (!Double.isFinite(total)) {
            return Ratios.unavailable(
                    null, "The hit and miss counters are too large to combine into a meaningful ratio.");
        }
        if (total <= 0.0) {
            return Ratios.unavailable(total, "No cache requests have been recorded yet.");
        }
        return new Ratios(total, hits / total, misses / total, null);
    }

    private static Double sanitize(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0.0) {
            return null;
        }
        return value;
    }

    private static String reasonOr(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason;
    }

    private record Ratios(Double total, Double hitRatio, Double missRatio, String reason) {

        static Ratios unavailable(Double total, String reason) {
            return new Ratios(total, null, null, reason);
        }
    }
}
