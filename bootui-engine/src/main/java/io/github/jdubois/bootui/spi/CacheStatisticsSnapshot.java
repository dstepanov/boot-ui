package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one labelled set of native cache counters, as read by an adapter from a cache
 * provider's own public statistics API.
 *
 * <p>Adapters report raw counters only. The engine {@code CacheService} owns presentation concerns: it derives
 * hit/miss ratios (and refuses to when the counters are not comparable), rejects impossible values, and shapes
 * the browser-facing {@code CacheStatisticsDto}.</p>
 *
 * <p>Every counter is nullable and {@code null} always means "this provider does not expose it" — never zero.
 * A provider that exposes an API but is not currently recording (Caffeine without {@code recordStats()},
 * Spring Data Redis without {@code enableStatistics()}) must report {@link #unavailable(String, String)} with
 * a reason rather than a series of zeroes.</p>
 *
 * @param available whether this provider is currently recording statistics for the described scope
 * @param provider a human-readable name of the statistics API (for example {@code Caffeine}), or {@code null}
 * @param window {@link #WINDOW_APPLICATION_LIFETIME} or {@link #WINDOW_UNKNOWN}
 * @param since an ISO-8601 instant the counters have been accumulating from, when the provider exposes one,
 *     otherwise {@code null}. Providers whose counters can be reset (Spring Data Redis) report the last reset
 *     here so a suddenly small counter is explainable; BootUI itself never resets a provider counter.
 * @param unavailableReason why no counters are available, or {@code null} when they are
 * @param requests the total request count when the provider exposes one directly, otherwise {@code null}
 * @param hits the hit count, or {@code null}
 * @param misses the miss count, or {@code null}
 * @param puts the put count, or {@code null}
 * @param evictions the size/expiry-driven eviction count, or {@code null}
 * @param removals the explicit removal count, or {@code null}
 * @param loadSuccesses the successful cache-loader count, or {@code null}
 * @param loadFailures the failed cache-loader count, or {@code null}
 * @param size the current entry count, or {@code null}
 * @param countersComparable whether {@code hits} and {@code misses} are recorded by the same counter family
 *     over the same window, and may therefore be combined into a ratio
 */
public record CacheStatisticsSnapshot(
        boolean available,
        String provider,
        String window,
        String since,
        String unavailableReason,
        Double requests,
        Double hits,
        Double misses,
        Double puts,
        Double evictions,
        Double removals,
        Double loadSuccesses,
        Double loadFailures,
        Double size,
        boolean countersComparable) {

    /** Counters accumulate for the life of the cache and are never reset by BootUI. */
    public static final String WINDOW_APPLICATION_LIFETIME = "APPLICATION_LIFETIME";

    /** The provider does not document the window its counters cover. */
    public static final String WINDOW_UNKNOWN = "UNKNOWN";

    /** No statistics API is available for this scope. */
    public static CacheStatisticsSnapshot unavailable(String provider, String reason) {
        return new CacheStatisticsSnapshot(
                false,
                provider,
                WINDOW_UNKNOWN,
                null,
                reason,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false);
    }

    /**
     * Starts describing an available counter set. The canonical constructor takes nine consecutive nullable
     * {@code Double} counters, which is easy to mis-order; adapters name each counter through this builder
     * instead, and any counter left unset stays {@code null} — "not exposed" rather than zero.
     */
    public static Builder recording(String provider, String window) {
        return new Builder(provider, window);
    }

    /** Names each counter an adapter sets, so a counter is never assigned to the wrong parameter. */
    public static final class Builder {

        private final String provider;

        private final String window;

        private String since;

        private Double requests;

        private Double hits;

        private Double misses;

        private Double puts;

        private Double evictions;

        private Double removals;

        private Double loadSuccesses;

        private Double loadFailures;

        private Double size;

        private boolean countersComparable;

        private Builder(String provider, String window) {
            this.provider = provider;
            this.window = window;
        }

        public Builder since(String value) {
            this.since = value;
            return this;
        }

        public Builder requests(long value) {
            this.requests = (double) value;
            return this;
        }

        public Builder hits(long value) {
            this.hits = (double) value;
            return this;
        }

        public Builder misses(long value) {
            this.misses = (double) value;
            return this;
        }

        public Builder puts(long value) {
            this.puts = (double) value;
            return this;
        }

        public Builder evictions(long value) {
            this.evictions = (double) value;
            return this;
        }

        public Builder removals(long value) {
            this.removals = (double) value;
            return this;
        }

        public Builder loadSuccesses(long value) {
            this.loadSuccesses = (double) value;
            return this;
        }

        public Builder loadFailures(long value) {
            this.loadFailures = (double) value;
            return this;
        }

        public Builder size(long value) {
            this.size = (double) value;
            return this;
        }

        /**
         * Declares that {@code hits} and {@code misses} come from the same counter family over the same
         * window, and may therefore be combined into a ratio by the engine.
         */
        public Builder countersComparable() {
            this.countersComparable = true;
            return this;
        }

        public CacheStatisticsSnapshot build() {
            return new CacheStatisticsSnapshot(
                    true,
                    provider,
                    window,
                    since,
                    null,
                    requests,
                    hits,
                    misses,
                    puts,
                    evictions,
                    removals,
                    loadSuccesses,
                    loadFailures,
                    size,
                    countersComparable);
        }
    }
}
