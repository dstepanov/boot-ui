package io.github.jdubois.bootui.spi;

import java.util.List;

/**
 * Framework-neutral snapshot of one cache manager known to a {@link CacheProvider}: its name, native
 * implementation type, no-op flag, wrapping/composition structure, dynamic-cache state and the caches it
 * currently exposes. The list is returned <em>unsorted</em> and <em>metrics-free</em> — the engine
 * {@code CacheService} applies BootUI's stable ordering and overlays Micrometer metrics on top.
 *
 * <p>Adapters with a single, unnamed cache manager (Quarkus) return a one-element list with a synthetic
 * manager name; adapters with multiple named manager beans (Spring) return one entry per bean.</p>
 *
 * @param name the manager name (a bean name on Spring, a synthetic constant on Quarkus)
 * @param type the fully-qualified class name of the manager implementation
 * @param noOp whether this is a no-op manager (caching effectively disabled)
 * @param composition {@link #COMPOSITION_SIMPLE}, {@link #COMPOSITION_COMPOSITE},
 *     {@link #COMPOSITION_DELEGATING} or {@link #COMPOSITION_UNKNOWN}
 * @param dynamicCaches {@link #DYNAMIC_YES}, {@link #DYNAMIC_NO} or {@link #DYNAMIC_UNKNOWN}. Adapters must
 *     never determine this by asking the manager for an unknown cache name, because a dynamic manager would
 *     then create that cache.
 * @param delegateTypes the fully-qualified class names of wrapped managers when the implementation exposes
 *     them through a public API; empty otherwise
 * @param caches the caches this manager currently exposes, unsorted and metrics-free
 */
public record CacheManagerSnapshot(
        String name,
        String type,
        boolean noOp,
        String composition,
        String dynamicCaches,
        List<String> delegateTypes,
        List<CacheSnapshot> caches) {

    /** The manager owns its caches directly. */
    public static final String COMPOSITION_SIMPLE = "SIMPLE";

    /** The manager delegates to several other cache managers. */
    public static final String COMPOSITION_COMPOSITE = "COMPOSITE";

    /** The manager wraps exactly one other cache manager. */
    public static final String COMPOSITION_DELEGATING = "DELEGATING";

    /** The implementation does not describe its structure through a public API. */
    public static final String COMPOSITION_UNKNOWN = "UNKNOWN";

    /** The manager creates caches on demand. */
    public static final String DYNAMIC_YES = "YES";

    /** The manager only exposes the caches it was configured with. */
    public static final String DYNAMIC_NO = "NO";

    /** The implementation does not state whether it creates caches on demand. */
    public static final String DYNAMIC_UNKNOWN = "UNKNOWN";

    public CacheManagerSnapshot {
        composition = composition == null ? COMPOSITION_UNKNOWN : composition;
        dynamicCaches = dynamicCaches == null ? DYNAMIC_UNKNOWN : dynamicCaches;
        delegateTypes = delegateTypes == null ? List.of() : List.copyOf(delegateTypes);
        caches = caches == null ? List.of() : List.copyOf(caches);
    }

    /**
     * A manager whose structure and dynamic-cache state are unknown, keeping the pre-tiering four-argument
     * shape working for adapters and tests that only report native topology.
     */
    public CacheManagerSnapshot(String name, String type, boolean noOp, List<CacheSnapshot> caches) {
        this(name, type, noOp, COMPOSITION_UNKNOWN, DYNAMIC_UNKNOWN, List.of(), caches);
    }
}
