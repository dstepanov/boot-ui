package io.github.jdubois.bootui.engine.hibernate;

import io.github.jdubois.bootui.core.dto.HibernateCacheRegionStatisticsDto;
import io.github.jdubois.bootui.core.dto.HibernateStatisticsDto;
import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.spi.HibernateCacheRegionSnapshot;
import io.github.jdubois.bootui.spi.HibernateStatisticsProvider;
import io.github.jdubois.bootui.spi.HibernateStatisticsSnapshot;

/**
 * Framework-neutral logic behind the Hibernate Statistics panel, additive to the static Hibernate
 * Advisor ({@link HibernateScanner}).
 *
 * <p>This service owns nothing but shaping: it asks a {@link HibernateStatisticsProvider} whether a
 * {@code SessionFactory} is reachable and has statistics collection enabled, and maps its
 * {@link HibernateStatisticsSnapshot} 1:1 onto the public {@link HibernateStatisticsDto}. When no
 * {@code SessionFactory} is available, or statistics collection is disabled, it serves a clear
 * unavailable report rather than fabricating data. In the disabled-statistics state it also exposes an
 * explicit runtime-enable action.</p>
 *
 * <p>The action only enables future collection on the current {@code SessionFactory}; it does not persist
 * configuration or reset existing counters.</p>
 */
public final class HibernateStatisticsService {

    private static final String NO_SESSION_FACTORY_REASON =
            "No Hibernate SessionFactory could be resolved. Configure a JPA persistence unit backed by "
                    + "Hibernate ORM to use this panel.";

    private static final String STATISTICS_DISABLED_REASON =
            "Hibernate statistics are disabled. Set hibernate.generate_statistics=true (or the Spring/Quarkus "
                    + "equivalent) to use this panel.";

    private final HibernateStatisticsProvider provider;

    public HibernateStatisticsService(HibernateStatisticsProvider provider) {
        this.provider = provider;
    }

    /** The statistics report: unavailable with a reason, or a live snapshot mapped onto the public DTO. */
    public HibernateStatisticsReport report() {
        if (provider == null || !provider.available()) {
            return new HibernateStatisticsReport(false, false, NO_SESSION_FACTORY_REASON, null);
        }
        if (!provider.statisticsEnabled()) {
            return new HibernateStatisticsReport(false, true, STATISTICS_DISABLED_REASON, null);
        }
        return new HibernateStatisticsReport(true, false, null, toDto(provider.snapshot()));
    }

    /** Enables statistics collection for the current runtime and returns the resulting live report. */
    public HibernateStatisticsReport enable() {
        if (provider == null || !provider.available()) {
            return new HibernateStatisticsReport(false, false, NO_SESSION_FACTORY_REASON, null);
        }
        provider.enableStatistics();
        return report();
    }

    private static HibernateStatisticsDto toDto(HibernateStatisticsSnapshot snapshot) {
        return new HibernateStatisticsDto(
                snapshot.sessionOpenCount(),
                snapshot.sessionCloseCount(),
                snapshot.flushCount(),
                snapshot.connectCount(),
                snapshot.transactionCount(),
                snapshot.successfulTransactionCount(),
                snapshot.entityLoadCount(),
                snapshot.entityFetchCount(),
                snapshot.entityInsertCount(),
                snapshot.entityUpdateCount(),
                snapshot.entityDeleteCount(),
                snapshot.collectionLoadCount(),
                snapshot.collectionFetchCount(),
                snapshot.collectionRecreateCount(),
                snapshot.collectionUpdateCount(),
                snapshot.collectionRemoveCount(),
                snapshot.queryExecutionCount(),
                snapshot.queryExecutionMaxTime(),
                snapshot.queryExecutionMaxTimeQueryString(),
                snapshot.queryCacheEnabled(),
                snapshot.queryCacheHitCount(),
                snapshot.queryCacheMissCount(),
                snapshot.queryCachePutCount(),
                snapshot.secondLevelCacheEnabled(),
                snapshot.secondLevelCacheHitCount(),
                snapshot.secondLevelCacheMissCount(),
                snapshot.secondLevelCachePutCount(),
                snapshot.secondLevelCacheRegions().stream()
                        .map(HibernateStatisticsService::toRegionDto)
                        .toList());
    }

    private static HibernateCacheRegionStatisticsDto toRegionDto(HibernateCacheRegionSnapshot region) {
        return new HibernateCacheRegionStatisticsDto(
                region.regionName(), region.hitCount(), region.missCount(), region.putCount());
    }
}
