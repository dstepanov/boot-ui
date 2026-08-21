package io.github.jdubois.bootui.autoconfigure.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Marker implemented by {@link CacheActivityCacheManager} so code that needs the real, undecorated
 * {@link CacheManager} — e.g. {@code SpringCacheProvider}'s topology/type inspection — can unwrap it, the
 * same way {@code SqlTracedDataSource} lets connection-pool discovery see past the SQL tracing proxy.
 */
interface CacheActivityAware {

    /** The real {@link CacheManager} this instance decorates. */
    CacheManager getTargetCacheManager();

    /** Unwraps {@code manager} if it is activity-decorated, otherwise returns it unchanged. */
    static CacheManager unwrap(CacheManager manager) {
        return manager instanceof CacheActivityAware aware ? aware.getTargetCacheManager() : manager;
    }

    /**
     * Unwraps {@code cache} if it is activity-decorated, otherwise returns it unchanged, so tier and
     * statistics inspection sees the provider's real {@link Cache} implementation (a {@code RedisCache}, for
     * example) rather than BootUI's recording decorator.
     */
    static Cache unwrap(Cache cache) {
        return cache instanceof CacheActivityCache activity ? activity.getTargetCache() : cache;
    }
}
