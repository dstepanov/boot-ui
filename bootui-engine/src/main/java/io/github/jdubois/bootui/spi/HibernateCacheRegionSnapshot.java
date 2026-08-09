package io.github.jdubois.bootui.spi;

/**
 * Framework-neutral snapshot of one Hibernate second-level cache region's hit/miss/put counters, as
 * reported by {@code org.hibernate.stat.CacheRegionStatistics}.
 *
 * @param regionName the cache region name
 * @param hitCount the number of cache hits for this region
 * @param missCount the number of cache misses for this region
 * @param putCount the number of cache puts for this region
 */
public record HibernateCacheRegionSnapshot(String regionName, long hitCount, long missCount, long putCount) {}
