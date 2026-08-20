package io.github.jdubois.bootui.engine.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheDto;
import io.github.jdubois.bootui.core.dto.CacheManagerDto;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.core.dto.CacheTierDto;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the framework-neutral {@link CacheService}. They pin the behavior that must stay
 * byte-identical to the pre-extraction Spring {@code SpringCacheService}: metric overlay + manager-tag
 * fallback, ordering, the clear status/HTTP-code matrix, cleared-entry naming, and — critically — the
 * cache-disappears-between-snapshot-and-eviction edge (clearOne → 404 "was not returned"; clearAll → skip,
 * never abort) plus the genuine-failure → 500 path.
 */
class CacheServiceTests {

    private static final Supplier<MeterRegistry> NO_REGISTRY = () -> null;

    private CacheService service(CacheProvider provider) {
        return new CacheService(provider, NO_REGISTRY, meter -> true);
    }

    private CacheService service(CacheProvider provider, MeterRegistry registry) {
        return new CacheService(provider, () -> registry, meter -> true);
    }

    @Test
    void nullProviderRendersUnavailableReportAndRefusesClear() {
        CacheService service = service(null);

        CacheReport report = service.report();
        assertThat(report.cacheAvailable()).isFalse();
        assertThat(report.managerCount()).isZero();
        assertThat(report.managers()).isEmpty();

        var response = service.clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("unavailable");
    }

    @Test
    void unavailableProviderStillSurfacesOperationsAndWarnings() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.available = false;
        provider.operations = new CacheOperationDiscovery(List.of(), List.of("a warning"));

        CacheReport report = service(provider).report();
        assertThat(report.cacheAvailable()).isFalse();
        assertThat(report.warnings()).containsExactly("a warning");
    }

    @Test
    void reportSortsManagersAndCachesAndCounts() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(
                new CacheManagerSnapshot(
                        "zManager",
                        "ZType",
                        false,
                        List.of(new CacheSnapshot("beta", "Native", 2L), new CacheSnapshot("alpha", "Native", 1L))),
                new CacheManagerSnapshot("aManager", "AType", false, List.of(new CacheSnapshot("solo", "Native", 0L))));

        CacheReport report = service(provider).report();
        assertThat(report.cacheAvailable()).isTrue();
        assertThat(report.managerCount()).isEqualTo(2);
        assertThat(report.cacheCount()).isEqualTo(3);
        assertThat(report.managers().stream().map(CacheManagerDto::name)).containsExactly("aManager", "zManager");
        assertThat(report.managers().get(1).caches().stream().map(CacheDto::name))
                .containsExactly("alpha", "beta");
    }

    @Test
    void reportOverlaysMicrometerMetricsWithManagerTagFallback() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager", "Type", false, List.of(new CacheSnapshot("orders", "Native", 5L))));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // No manager tag at all → engine falls back to the "*" wildcard key, which still resolves the cache.
        registry.counter("cache.gets", Tags.of("cache", "orders", "result", "hit"))
                .increment(8);
        registry.counter("cache.gets", Tags.of("cache", "orders", "result", "miss"))
                .increment(2);

        CacheReport report = service(provider, registry).report();
        CacheDto cache = report.managers().get(0).caches().get(0);
        assertThat(cache.metrics().available()).isTrue();
        assertThat(cache.metrics().hits()).isEqualTo(8.0);
        assertThat(cache.metrics().misses()).isEqualTo(2.0);
        assertThat(cache.metrics().hitRatio()).isEqualTo(0.8);
    }

    @Test
    void legacySnapshotsWithoutTiersRenderAsOpaqueWithAReason() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager", "Type", false, List.of(new CacheSnapshot("orders", "Native", 5L))));

        CacheDto cache = service(provider).report().managers().get(0).caches().get(0);
        assertThat(cache.tiers()).isEmpty();
        assertThat(cache.opaque()).isTrue();
        assertThat(cache.opaqueReason()).isNotBlank();
        assertThat(cache.statistics().available()).isFalse();
        assertThat(cache.statistics().unavailableReason()).isNotBlank();
    }

    @Test
    void keepsTheAdapterOpaqueReasonWhenItSuppliedOne() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot("orders", "Native", null, List.of(), null, "Vendor cache is sealed."))));

        CacheDto cache = service(provider).report().managers().get(0).caches().get(0);
        assertThat(cache.opaqueReason()).isEqualTo("Vendor cache is sealed.");
    }

    @Test
    void ordersTiersByLevelThenIdAndCountsThem() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot(
                        "orders",
                        "Native",
                        5L,
                        List.of(tier("remote", 1), tier("zLocal", 0), tier("aLocal", 0)),
                        CacheStatisticsSnapshot.unavailable("Vendor", "No whole-cache counters."),
                        null))));

        CacheReport report = service(provider).report();
        CacheDto cache = report.managers().get(0).caches().get(0);
        assertThat(cache.tiers().stream().map(CacheTierDto::id)).containsExactly("aLocal", "zLocal", "remote");
        assertThat(cache.opaque()).isFalse();
        assertThat(cache.opaqueReason()).isNull();
        assertThat(report.tierCount()).isEqualTo(3);
        assertThat(report.truncated()).isFalse();
    }

    @Test
    void passesManagerCompositionAndDynamicStateThrough() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                CacheManagerSnapshot.COMPOSITION_COMPOSITE,
                CacheManagerSnapshot.DYNAMIC_NO,
                List.of("com.example.LocalManager", "com.example.RemoteManager"),
                List.of(new CacheSnapshot("orders", "Native", 5L))));

        CacheManagerDto manager = service(provider).report().managers().get(0);
        assertThat(manager.composition()).isEqualTo(CacheManagerSnapshot.COMPOSITION_COMPOSITE);
        assertThat(manager.dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_NO);
        assertThat(manager.delegateTypes()).containsExactly("com.example.LocalManager", "com.example.RemoteManager");
    }

    @Test
    void defaultsAnUnstatedManagerCompositionAndDynamicStateToUnknown() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager", "Type", false, null, null, null, List.of(new CacheSnapshot("orders", "Native", 5L))));

        CacheManagerDto manager = service(provider).report().managers().get(0);
        assertThat(manager.composition()).isEqualTo(CacheManagerSnapshot.COMPOSITION_UNKNOWN);
        assertThat(manager.dynamicCaches()).isEqualTo(CacheManagerSnapshot.DYNAMIC_UNKNOWN);
        assertThat(manager.delegateTypes()).isEmpty();
    }

    @Test
    void truncatesManagersCachesAndTiersAndSaysSoInTheWarnings() {
        FakeCacheProvider provider = new FakeCacheProvider();
        List<CacheTierSnapshot> tiers = new ArrayList<>();
        for (int i = 0; i < CacheService.MAX_TIERS_PER_CACHE + 3; i++) {
            tiers.add(tier(String.format("tier-%03d", i), i));
        }
        List<CacheSnapshot> caches = new ArrayList<>();
        for (int i = 0; i < CacheService.MAX_CACHES_PER_MANAGER + 2; i++) {
            caches.add(new CacheSnapshot(
                    String.format("cache-%04d", i),
                    "Native",
                    1L,
                    i == 0 ? tiers : List.of(tier("only", 0)),
                    null,
                    null));
        }
        List<CacheManagerSnapshot> managers = new ArrayList<>();
        for (int i = 0; i < CacheService.MAX_MANAGERS + 1; i++) {
            managers.add(new CacheManagerSnapshot(
                    String.format("manager-%03d", i), "Type", false, i == 0 ? caches : List.of()));
        }
        provider.managers = managers;

        CacheReport report = service(provider).report();
        assertThat(report.managerCount()).isEqualTo(CacheService.MAX_MANAGERS);
        assertThat(report.managers().get(0).caches()).hasSize(CacheService.MAX_CACHES_PER_MANAGER);
        assertThat(report.managers().get(0).caches().get(0).tiers()).hasSize(CacheService.MAX_TIERS_PER_CACHE);
        assertThat(report.truncated()).isTrue();
        assertThat(report.warnings()).hasSize(3);
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("cache managers"));
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("caches"));
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("tiers"));
    }

    private static CacheTierSnapshot tier(String id, int level) {
        return new CacheTierSnapshot(
                id, id, level, "com.example.Store", CacheTierSnapshot.LOCALITY_LOCAL, null, null, null, null);
    }

    @Test
    void clearDisabledIsRejectedWith409() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.clearEnabled = false;

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("disabled");
    }

    @Test
    void clearWithoutConfirmationIsRejectedWith400() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest(null, null, true, false));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("confirmation_required");
    }

    @Test
    void clearReportsUnavailableReasonWith409() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.clearUnavailableReason = Optional.of("No CacheManager beans are available.");

        var response = provider.clear(true, true);
        assertThat(response.status()).isEqualTo(409);
        assertThat(response.body().status()).isEqualTo("unavailable");
        assertThat(response.body().message()).isEqualTo("No CacheManager beans are available.");
    }

    @Test
    void clearOneRequiresManagerAndCacheName() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest(null, null, false, true));
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().status()).isEqualTo("invalid_request");
    }

    @Test
    void clearOneUnknownManagerIs404() {
        var response = service(new FakeCacheProvider()).clear(new CacheClearRequest("missing", "orders", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().message()).contains("Cache manager 'missing' was not found.");
    }

    @Test
    void clearOneUnknownCacheIs404() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "ghost", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().message()).contains("was not found in manager 'cacheManager'");
    }

    @Test
    void clearOneEvictsAndReportsClearedEntry() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("cleared");
        assertThat(response.body().caches()).containsExactly("cacheManager/orders");
        assertThat(provider.evicted).containsExactly("cacheManager/orders");
    }

    @Test
    void clearAllEvictsEveryCacheWithPluralizedMessage() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot("orders", "Native", 1L), new CacheSnapshot("customers", "Native", 1L))));

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().clearedCaches()).isEqualTo(2);
        assertThat(response.body().message()).isEqualTo("Cleared 2 caches.");
        // Sorted: customers before orders.
        assertThat(response.body().caches()).containsExactly("cacheManager/customers", "cacheManager/orders");
    }

    @Test
    void clearAllReportsAnExactCountButABoundedListOfNames() {
        // Clear-all deliberately clears past the report's truncation, so the reply has to stay bounded too:
        // the count above the list is exact, the list itself is capped and says how much it left out.
        int total = CacheService.MAX_CLEARED_NAMES + 25;
        List<CacheSnapshot> caches = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            caches.add(new CacheSnapshot("cache-" + String.format("%04d", index), "Native", 1L));
        }
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot("cacheManager", "Type", false, caches));

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().message()).isEqualTo("Cleared " + total + " caches.");
        assertThat(provider.evicted).hasSize(total);
        assertThat(response.body().caches()).hasSize(CacheService.MAX_CLEARED_NAMES + 1);
        assertThat(response.body().caches().get(CacheService.MAX_CLEARED_NAMES)).isEqualTo("... and 25 more");
    }

    @Test
    void clearAllSingularMessageForOneCache() {
        var response = singleCache("cacheManager", "orders").clear(true, true);
        assertThat(response.body().message()).isEqualTo("Cleared 1 cache.");
    }

    @Test
    void clearOneOnCacheThatVanishedReturns404NotFound() {
        // The cache is known at snapshot time but the provider reports it absent at eviction (TOCTOU race).
        FakeCacheProvider provider = singleCache("cacheManager", "orders");
        provider.evictReturnsFalse = true;

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.body().status()).isEqualTo("not_found");
        assertThat(response.body().message()).contains("was not returned by manager 'cacheManager'");
    }

    @Test
    void clearAllSkipsAVanishedCacheInsteadOfAborting() {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(new CacheManagerSnapshot(
                "cacheManager",
                "Type",
                false,
                List.of(new CacheSnapshot("orders", "Native", 1L), new CacheSnapshot("vanished", "Native", 1L))));
        provider.evictFalseFor = "vanished";

        var response = service(provider).clear(new CacheClearRequest(null, null, true, true));
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().status()).isEqualTo("cleared");
        // The vanished cache is skipped; the rest are still cleared (no abort).
        assertThat(response.body().caches()).containsExactly("cacheManager/orders");
    }

    @Test
    void clearMapsGenuineEvictionFailureTo500() {
        FakeCacheProvider provider = singleCache("cacheManager", "orders");
        provider.evictThrows = true;

        var response = service(provider).clear(new CacheClearRequest("cacheManager", "orders", false, true));
        assertThat(response.status()).isEqualTo(500);
        assertThat(response.body().status()).isEqualTo("failed");
        assertThat(response.body().message()).contains("(IllegalStateException)");
    }

    private FakeCacheProvider singleCache(String manager, String cache) {
        FakeCacheProvider provider = new FakeCacheProvider();
        provider.managers = List.of(
                new CacheManagerSnapshot(manager, "Type", false, List.of(new CacheSnapshot(cache, "Native", 1L))));
        return provider;
    }

    /** Package-private fake so the engine test needs no Spring/Quarkus backend. */
    private static final class FakeCacheProvider implements CacheProvider {

        private boolean available = true;
        private boolean clearEnabled = true;
        private List<CacheManagerSnapshot> managers = List.of();
        private CacheOperationDiscovery operations = CacheOperationDiscovery.empty();
        private Optional<String> clearUnavailableReason = Optional.empty();
        private boolean evictReturnsFalse;
        private boolean evictThrows;
        private String evictFalseFor;
        private final List<String> evicted = new ArrayList<>();

        CacheService asService() {
            return new CacheService(this, NO_REGISTRY, meter -> true);
        }

        CacheClearResponse clear(boolean all, boolean confirm) {
            return asService().clear(new CacheClearRequest(null, null, all, confirm));
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean clearEnabled() {
            return clearEnabled;
        }

        @Override
        public List<CacheManagerSnapshot> managers() {
            return managers;
        }

        @Override
        public CacheOperationDiscovery operations() {
            return operations;
        }

        @Override
        public Optional<String> clearUnavailableReason() {
            return clearUnavailableReason;
        }

        @Override
        public boolean evict(String managerName, String cacheName) {
            if (evictThrows) {
                throw new IllegalStateException("boom");
            }
            if (evictReturnsFalse || cacheName.equals(evictFalseFor)) {
                return false;
            }
            evicted.add(managerName + "/" + cacheName);
            return true;
        }
    }
}
