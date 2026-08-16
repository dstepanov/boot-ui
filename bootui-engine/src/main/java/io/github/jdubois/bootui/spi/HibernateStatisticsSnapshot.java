package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of a Hibernate {@code SessionFactory}'s runtime statistics, read from
 * {@code org.hibernate.stat.Statistics} by a {@link HibernateStatisticsProvider}. Carries only raw
 * counters (no ordering, filtering, or shaping) — the engine {@code HibernateStatisticsService} maps this
 * 1:1 onto the public {@code HibernateStatisticsDto}.
 *
 * @param secondLevelCacheRegions per-region second-level cache counters, unsorted; empty when the
 *     second-level cache is disabled or no regions have been used yet
 */
public record HibernateStatisticsSnapshot(
        long sessionOpenCount,
        long sessionCloseCount,
        long flushCount,
        long connectCount,
        long transactionCount,
        long successfulTransactionCount,
        long entityLoadCount,
        long entityFetchCount,
        long entityInsertCount,
        long entityUpdateCount,
        long entityDeleteCount,
        long collectionLoadCount,
        long collectionFetchCount,
        long collectionRecreateCount,
        long collectionUpdateCount,
        long collectionRemoveCount,
        long queryExecutionCount,
        long queryExecutionMaxTime,
        String queryExecutionMaxTimeQueryString,
        boolean queryCacheEnabled,
        long queryCacheHitCount,
        long queryCacheMissCount,
        long queryCachePutCount,
        boolean secondLevelCacheEnabled,
        long secondLevelCacheHitCount,
        long secondLevelCacheMissCount,
        long secondLevelCachePutCount,
        List<HibernateCacheRegionSnapshot> secondLevelCacheRegions) {

    public HibernateStatisticsSnapshot {
        secondLevelCacheRegions = secondLevelCacheRegions == null ? List.of() : List.copyOf(secondLevelCacheRegions);
    }
}
