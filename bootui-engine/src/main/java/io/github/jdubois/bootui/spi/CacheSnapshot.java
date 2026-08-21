package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of one cache known to a {@link CacheProvider}, carrying only what the adapter can
 * read through public, supported APIs: the cache's name, native implementation type, an estimated entry count,
 * its safely discoverable backing tiers and its whole-cache native statistics.
 *
 * <p>Live Micrometer metrics are deliberately <em>not</em> part of this snapshot: the engine
 * {@code CacheService} overlays them on top from the shared {@code MeterRegistry} so the same metric-reading
 * code serves every adapter.</p>
 *
 * <p>A cache whose implementation does not describe its storage returns an empty {@link #tiers()} list and an
 * {@link #opaqueReason()}; the engine then marks it opaque rather than inventing a tier for it.</p>
 *
 * @param name the cache name
 * @param nativeType the fully-qualified class name of the underlying native cache, or {@code null} when it
 *     cannot be determined
 * @param size an estimated entry count when the native cache exposes one, otherwise {@code null}
 * @param tiers the discovered backing tiers, possibly empty
 * @param statistics whole-cache native statistics; never {@code null}
 * @param opaqueReason why no tier could be described, or {@code null} when {@link #tiers()} is populated
 */
public record CacheSnapshot(
        String name,
        String nativeType,
        Long size,
        List<CacheTierSnapshot> tiers,
        CacheStatisticsSnapshot statistics,
        String opaqueReason) {

    public CacheSnapshot {
        tiers = tiers == null ? List.of() : List.copyOf(tiers);
        statistics = statistics == null
                ? CacheStatisticsSnapshot.unavailable(null, "No native statistics API is exposed for this cache.")
                : statistics;
    }

    /**
     * A cache with no discoverable tier structure or native statistics, keeping the pre-tiering three-argument
     * shape working for adapters and tests that only report native topology.
     */
    public CacheSnapshot(String name, String nativeType, Long size) {
        this(name, nativeType, size, List.of(), null, "This cache implementation does not describe its storage.");
    }
}
