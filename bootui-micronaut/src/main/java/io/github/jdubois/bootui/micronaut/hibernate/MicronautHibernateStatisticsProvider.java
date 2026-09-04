package io.github.jdubois.bootui.micronaut.hibernate;

import io.github.jdubois.bootui.spi.HibernateCacheRegionSnapshot;
import io.github.jdubois.bootui.spi.HibernateStatisticsProvider;
import io.github.jdubois.bootui.spi.HibernateStatisticsSnapshot;
import io.micronaut.context.BeanContext;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.boot.spi.SessionFactoryOptions;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.stat.CacheRegionStatistics;
import org.hibernate.stat.Statistics;

/**
 * Micronaut {@link HibernateStatisticsProvider} over the application's Hibernate {@code SessionFactory}.
 *
 * <p>The Micronaut analogue of the Quarkus and Spring providers, reached through the standard JPA
 * {@code EntityManagerFactory} unwrap so it works for any Hibernate-backed persistence unit rather than only
 * a Micronaut-Data-configured one.
 *
 * <p>The mapping below is byte-identical to the other adapters' — deliberately duplicated rather than shared,
 * because it reads {@code org.hibernate.stat.Statistics}, an optional Hibernate type that must stay
 * module-local so the engine never links it.
 */
public final class MicronautHibernateStatisticsProvider implements HibernateStatisticsProvider {

    private final BeanContext beanContext;

    public MicronautHibernateStatisticsProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        return sessionFactory() != null;
    }

    @Override
    public boolean statisticsEnabled() {
        SessionFactory sessionFactory = sessionFactory();
        return sessionFactory != null && sessionFactory.getStatistics().isStatisticsEnabled();
    }

    @Override
    public void enableStatistics() {
        SessionFactory sessionFactory = sessionFactory();
        if (sessionFactory == null) {
            throw new IllegalStateException("No Hibernate SessionFactory could be resolved.");
        }
        sessionFactory.getStatistics().setStatisticsEnabled(true);
    }

    @Override
    public HibernateStatisticsSnapshot snapshot() {
        SessionFactory sessionFactory = sessionFactory();
        return sessionFactory == null ? null : map(sessionFactory);
    }

    private SessionFactory sessionFactory() {
        if (beanContext == null) {
            return null;
        }
        try {
            EntityManagerFactory entityManagerFactory =
                    beanContext.findBean(EntityManagerFactory.class).orElse(null);
            return entityManagerFactory == null ? null : entityManagerFactory.unwrap(SessionFactory.class);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static HibernateStatisticsSnapshot map(SessionFactory sessionFactory) {
        Statistics statistics = sessionFactory.getStatistics();
        // Counters stay at zero until a query or cache is actually used, so an activity-based heuristic would
        // misreport an enabled-but-unused cache as disabled. SessionFactoryOptions carries the authoritative
        // configured flag; the heuristic is only a fallback for a non-standard SessionFactory.
        boolean queryCacheEnabled;
        boolean secondLevelCacheEnabled;
        SessionFactoryOptions options = sessionFactoryOptions(sessionFactory);
        if (options != null) {
            queryCacheEnabled = options.isQueryCacheEnabled();
            secondLevelCacheEnabled = options.isSecondLevelCacheEnabled();
        } else {
            queryCacheEnabled = statistics.getQueryCacheHitCount() > 0
                    || statistics.getQueryCacheMissCount() > 0
                    || statistics.getQueryCachePutCount() > 0;
            secondLevelCacheEnabled = statistics.getSecondLevelCacheRegionNames().length > 0;
        }
        // The region names include the query-result-cache region, for which getDomainDataRegionStatistics
        // throws; those are skipped, and the query cache's own counters are reported above instead.
        String[] regionNames = statistics.getSecondLevelCacheRegionNames();
        List<HibernateCacheRegionSnapshot> regions = new ArrayList<>(regionNames.length);
        for (String regionName : regionNames) {
            CacheRegionStatistics regionStatistics;
            try {
                regionStatistics = statistics.getDomainDataRegionStatistics(regionName);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            regions.add(new HibernateCacheRegionSnapshot(
                    regionName,
                    regionStatistics.getHitCount(),
                    regionStatistics.getMissCount(),
                    regionStatistics.getPutCount()));
        }
        return new HibernateStatisticsSnapshot(
                statistics.getSessionOpenCount(),
                statistics.getSessionCloseCount(),
                statistics.getFlushCount(),
                statistics.getConnectCount(),
                statistics.getTransactionCount(),
                statistics.getSuccessfulTransactionCount(),
                statistics.getEntityLoadCount(),
                statistics.getEntityFetchCount(),
                statistics.getEntityInsertCount(),
                statistics.getEntityUpdateCount(),
                statistics.getEntityDeleteCount(),
                statistics.getCollectionLoadCount(),
                statistics.getCollectionFetchCount(),
                statistics.getCollectionRecreateCount(),
                statistics.getCollectionUpdateCount(),
                statistics.getCollectionRemoveCount(),
                statistics.getQueryExecutionCount(),
                statistics.getQueryExecutionMaxTime(),
                statistics.getQueryExecutionMaxTimeQueryString(),
                queryCacheEnabled,
                statistics.getQueryCacheHitCount(),
                statistics.getQueryCacheMissCount(),
                statistics.getQueryCachePutCount(),
                secondLevelCacheEnabled,
                statistics.getSecondLevelCacheHitCount(),
                statistics.getSecondLevelCacheMissCount(),
                statistics.getSecondLevelCachePutCount(),
                regions);
    }

    private static SessionFactoryOptions sessionFactoryOptions(SessionFactory sessionFactory) {
        try {
            return sessionFactory.unwrap(SessionFactoryImplementor.class).getSessionFactoryOptions();
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
