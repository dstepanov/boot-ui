package io.github.jdubois.bootui.micronaut.cache;

import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import io.micronaut.cache.CacheManager;
import io.micronaut.cache.SyncCache;
import io.micronaut.context.BeanContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Micronaut {@link CacheProvider} over the application's {@link CacheManager}.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code CacheManager} reader and the Quarkus adapter's
 * {@code QuarkusCacheProvider}. It enumerates the configured caches and, where the underlying store exposes
 * one, their native size — Micronaut's default cache is Caffeine, whose native cache is reachable through
 * {@link SyncCache#getNativeCache()}.
 *
 * <p>Statistics are reported as unavailable rather than invented: Micronaut's cache abstraction has no
 * statistics API, and Caffeine only records them when the application explicitly enables
 * {@code recordStats}. Saying so is more useful to a developer than a page of zeroes that look like real
 * measurements.
 *
 * <p>Eviction is supported ({@link SyncCache#invalidateAll()}), which is what makes the panel's clear action
 * work; it is still gated by BootUI's per-panel read-only policy like every other action.
 */
public final class MicronautCacheProvider implements CacheProvider {

    /** The single logical manager name the panel groups Micronaut's caches under. */
    static final String MANAGER_NAME = "micronautCacheManager";

    static final String NO_STATISTICS_REASON =
            "Micronaut's cache abstraction exposes no statistics API. With the default Caffeine store,"
                    + " enable recordStats on the cache to collect them.";

    private final BeanContext beanContext;

    public MicronautCacheProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        return cacheManager() != null;
    }

    @Override
    public boolean clearEnabled() {
        return available();
    }

    @Override
    public Optional<String> clearUnavailableReason() {
        return available() ? Optional.empty() : Optional.of("Micronaut Cache is not configured.");
    }

    @Override
    public List<CacheManagerSnapshot> managers() {
        CacheManager<?> manager = cacheManager();
        if (manager == null) {
            return List.of();
        }
        List<CacheSnapshot> caches = new ArrayList<>();
        for (String name : manager.getCacheNames()) {
            caches.add(snapshot(manager, name));
        }
        return List.of(new CacheManagerSnapshot(
                MANAGER_NAME,
                manager.getClass().getName(),
                false,
                CacheManagerSnapshot.COMPOSITION_SIMPLE,
                // Micronaut resolves a cache by name from configuration; it does not create caches on demand.
                CacheManagerSnapshot.DYNAMIC_NO,
                List.of(),
                List.copyOf(caches)));
    }

    @Override
    public CacheOperationDiscovery operations() {
        // Micronaut's @Cacheable/@CachePut/@CacheInvalidate advice is applied at compile time and exposes no
        // runtime registry of the operations it wove, so the panel shows the caches themselves rather than
        // claiming an operation inventory it cannot see.
        return CacheOperationDiscovery.empty();
    }

    @Override
    public boolean evict(String managerName, String cacheName) {
        CacheManager<?> manager = cacheManager();
        if (manager == null || cacheName == null || cacheName.isBlank()) {
            return false;
        }
        try {
            SyncCache<?> cache = manager.getCache(cacheName);
            if (cache == null) {
                return false;
            }
            cache.invalidateAll();
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private CacheSnapshot snapshot(CacheManager<?> manager, String name) {
        try {
            SyncCache<?> cache = manager.getCache(name);
            Object nativeCache = cache == null ? null : cache.getNativeCache();
            return new CacheSnapshot(
                    name,
                    nativeCache == null ? null : nativeCache.getClass().getName(),
                    estimatedSize(nativeCache),
                    List.of(),
                    CacheStatisticsSnapshot.unavailable(
                            nativeCache == null ? null : nativeCache.getClass().getSimpleName(), NO_STATISTICS_REASON),
                    null);
        } catch (RuntimeException ex) {
            return new CacheSnapshot(name, null, null);
        }
    }

    /**
     * The number of entries a native cache reports, when it can. Caffeine exposes {@code estimatedSize()};
     * a store that exposes nothing comparable reports {@code null}, which the panel renders as unknown
     * rather than as zero.
     */
    private static Long estimatedSize(Object nativeCache) {
        if (nativeCache == null) {
            return null;
        }
        try {
            var method = nativeCache.getClass().getMethod("estimatedSize");
            Object value = method.invoke(nativeCache);
            return value instanceof Number number ? number.longValue() : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private CacheManager<?> cacheManager() {
        if (beanContext == null) {
            return null;
        }
        try {
            return beanContext.findBean(CacheManager.class).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
