package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one backing tier of a cache, as far as the cache implementation exposes it
 * through public, supported APIs.
 *
 * <p>Adapters return tiers in the order they are consulted; the engine sorts by {@link #level()} and then
 * {@link #id()} so the report is deterministic even when an adapter returns them unordered. An adapter that
 * cannot describe a cache's storage returns no tiers at all rather than inventing one.</p>
 *
 * @param id a stable identity for the tier within its cache
 * @param name a short display name for the tier
 * @param level the parent/child order, {@code 0} being the tier consulted first
 * @param implementationType the fully-qualified class name backing this tier, or {@code null}
 * @param locality {@link #LOCALITY_LOCAL}, {@link #LOCALITY_DISTRIBUTED} or {@link #LOCALITY_UNKNOWN}; only
 *     ever set when the implementation explicitly exposes it, never inferred from a class name
 * @param maximumSize the configured maximum entry count, or {@code null} when not exposed
 * @param expiryPolicy a human-readable summary of the configured expiry, or {@code null} when not exposed
 * @param policyNote a caveat about how {@code maximumSize} and {@code expiryPolicy} were obtained, or
 *     {@code null} when they are read straight from the running cache and need no qualification
 * @param statistics native statistics for this tier; never {@code null}
 */
public record CacheTierSnapshot(
        String id,
        String name,
        int level,
        String implementationType,
        String locality,
        Long maximumSize,
        String expiryPolicy,
        String policyNote,
        CacheStatisticsSnapshot statistics) {

    /** The tier stores entries inside this JVM. */
    public static final String LOCALITY_LOCAL = "LOCAL";

    /** The tier stores entries in a remote, potentially shared system. */
    public static final String LOCALITY_DISTRIBUTED = "DISTRIBUTED";

    /** The implementation does not state where the tier stores entries. */
    public static final String LOCALITY_UNKNOWN = "UNKNOWN";

    public CacheTierSnapshot {
        locality = locality == null ? LOCALITY_UNKNOWN : locality;
        statistics = statistics == null
                ? CacheStatisticsSnapshot.unavailable(null, "No statistics API is exposed for this tier.")
                : statistics;
    }
}
