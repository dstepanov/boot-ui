package io.github.jdubois.bootui.engine.hibernate;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.spi.HibernateCacheRegionSnapshot;
import io.github.jdubois.bootui.spi.HibernateStatisticsProvider;
import io.github.jdubois.bootui.spi.HibernateStatisticsSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link HibernateStatisticsService}: the availability gating
 * (no provider / no SessionFactory / statistics disabled) and the 1:1 snapshot-to-DTO mapping.
 */
class HibernateStatisticsServiceTests {

    private static final HibernateStatisticsSnapshot SNAPSHOT = new HibernateStatisticsSnapshot(
            10, // sessionOpenCount
            9, // sessionCloseCount
            7, // flushCount
            3, // connectCount
            5, // transactionCount
            4, // successfulTransactionCount
            20, // entityLoadCount
            15, // entityFetchCount
            6, // entityInsertCount
            2, // entityUpdateCount
            1, // entityDeleteCount
            8, // collectionLoadCount
            4, // collectionFetchCount
            0, // collectionRecreateCount
            2, // collectionUpdateCount
            1, // collectionRemoveCount
            30, // queryExecutionCount
            250, // queryExecutionMaxTime
            "select 1", // queryExecutionMaxTimeQueryString
            true, // queryCacheEnabled
            12, // queryCacheHitCount
            3, // queryCacheMissCount
            5, // queryCachePutCount
            true, // secondLevelCacheEnabled
            40, // secondLevelCacheHitCount
            6, // secondLevelCacheMissCount
            10, // secondLevelCachePutCount
            List.of(new HibernateCacheRegionSnapshot("com.example.Widget", 25, 4, 6)));

    @Test
    void reportsUnavailableWhenNoProvider() {
        HibernateStatisticsReport report = new HibernateStatisticsService(null).report();

        assertThat(report.available()).isFalse();
        assertThat(report.statistics()).isNull();
        assertThat(report.unavailableReason()).isNotBlank();
    }

    @Test
    void reportsUnavailableWhenNoSessionFactory() {
        HibernateStatisticsProvider provider = new StubProvider(false, true, SNAPSHOT);

        HibernateStatisticsReport report = new HibernateStatisticsService(provider).report();

        assertThat(report.available()).isFalse();
        assertThat(report.statistics()).isNull();
        assertThat(report.unavailableReason()).contains("SessionFactory");
    }

    @Test
    void reportsUnavailableWhenStatisticsDisabled() {
        HibernateStatisticsProvider provider = new StubProvider(true, false, SNAPSHOT);

        HibernateStatisticsReport report = new HibernateStatisticsService(provider).report();

        assertThat(report.available()).isFalse();
        assertThat(report.statistics()).isNull();
        assertThat(report.unavailableReason()).contains("generate_statistics");
    }

    @Test
    void mapsSnapshotOntoDtoWhenAvailableAndEnabled() {
        HibernateStatisticsProvider provider = new StubProvider(true, true, SNAPSHOT);

        HibernateStatisticsReport report = new HibernateStatisticsService(provider).report();

        assertThat(report.available()).isTrue();
        assertThat(report.unavailableReason()).isNull();
        assertThat(report.statistics().sessionOpenCount()).isEqualTo(10);
        assertThat(report.statistics().sessionCloseCount()).isEqualTo(9);
        assertThat(report.statistics().flushCount()).isEqualTo(7);
        assertThat(report.statistics().connectCount()).isEqualTo(3);
        assertThat(report.statistics().transactionCount()).isEqualTo(5);
        assertThat(report.statistics().successfulTransactionCount()).isEqualTo(4);
        assertThat(report.statistics().entityLoadCount()).isEqualTo(20);
        assertThat(report.statistics().entityFetchCount()).isEqualTo(15);
        assertThat(report.statistics().entityInsertCount()).isEqualTo(6);
        assertThat(report.statistics().entityUpdateCount()).isEqualTo(2);
        assertThat(report.statistics().entityDeleteCount()).isEqualTo(1);
        assertThat(report.statistics().collectionLoadCount()).isEqualTo(8);
        assertThat(report.statistics().collectionFetchCount()).isEqualTo(4);
        assertThat(report.statistics().collectionRecreateCount()).isEqualTo(0);
        assertThat(report.statistics().collectionUpdateCount()).isEqualTo(2);
        assertThat(report.statistics().collectionRemoveCount()).isEqualTo(1);
        assertThat(report.statistics().queryExecutionCount()).isEqualTo(30);
        assertThat(report.statistics().queryExecutionMaxTime()).isEqualTo(250);
        assertThat(report.statistics().queryExecutionMaxTimeQueryString()).isEqualTo("select 1");
        assertThat(report.statistics().queryCacheEnabled()).isTrue();
        assertThat(report.statistics().queryCacheHitCount()).isEqualTo(12);
        assertThat(report.statistics().queryCacheMissCount()).isEqualTo(3);
        assertThat(report.statistics().queryCachePutCount()).isEqualTo(5);
        assertThat(report.statistics().secondLevelCacheEnabled()).isTrue();
        assertThat(report.statistics().secondLevelCacheHitCount()).isEqualTo(40);
        assertThat(report.statistics().secondLevelCacheMissCount()).isEqualTo(6);
        assertThat(report.statistics().secondLevelCachePutCount()).isEqualTo(10);
        assertThat(report.statistics().secondLevelCacheRegions()).hasSize(1);
        assertThat(report.statistics().secondLevelCacheRegions().get(0).regionName())
                .isEqualTo("com.example.Widget");
        assertThat(report.statistics().secondLevelCacheRegions().get(0).hitCount())
                .isEqualTo(25);
        assertThat(report.statistics().secondLevelCacheRegions().get(0).missCount())
                .isEqualTo(4);
        assertThat(report.statistics().secondLevelCacheRegions().get(0).putCount())
                .isEqualTo(6);
    }

    private record StubProvider(boolean available, boolean statisticsEnabled, HibernateStatisticsSnapshot snapshot)
            implements HibernateStatisticsProvider {

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean statisticsEnabled() {
            return statisticsEnabled;
        }

        @Override
        public HibernateStatisticsSnapshot snapshot() {
            return snapshot;
        }
    }
}
