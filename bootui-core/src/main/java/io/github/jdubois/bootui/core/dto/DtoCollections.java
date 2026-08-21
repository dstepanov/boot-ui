package io.github.jdubois.bootui.core.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defensive-copy helpers shared by the core DTO records.
 *
 * <p>Core DTOs are documented as immutable, so every collection component is copied in the record's
 * compact constructor. Without the copy a caller could keep a reference to the collection it passed
 * in, or mutate the collection returned by an accessor, and change a report after it was published.</p>
 *
 * <p>The copies deliberately do not use {@link List#copyOf(java.util.Collection)} or
 * {@link Map#copyOf(Map)}:</p>
 *
 * <ul>
 *   <li>{@code Map.copyOf} produces a hash-ordered map whose iteration order is not the insertion
 *       order and is randomized per JVM run, which would make the serialized JSON key order unstable
 *       and different between Jackson 2 and Jackson 3 runs. A {@link LinkedHashMap} copy keeps the
 *       caller's order, so the emitted bytes stay identical across both Jackson generations.</li>
 *   <li>Both {@code copyOf} methods reject {@code null} elements, values, and keys. Panel data is
 *       assembled from JVM and framework sources that can legitimately yield a {@code null} element,
 *       and a DTO constructor is the wrong place to turn that into a request failure.</li>
 * </ul>
 *
 * <p>{@code null} is normalized to an empty collection: no core DTO documents a nullable collection
 * component, and the existing DTOs already used that convention.</p>
 *
 * <p>The copies are shallow. Elements are themselves immutable DTO records, boxed scalars, or
 * strings, so a shallow copy is enough for every typed component. The only exception is a
 * {@code Map<String, Object>} value supplied by a framework, which BootUI does not attempt to
 * deep-copy.</p>
 */
final class DtoCollections {

    private DtoCollections() {}

    /**
     * Returns an unmodifiable copy of {@code values}, preserving iteration order.
     *
     * @param values the caller-owned list, possibly {@code null}
     * @param <T> the element type
     * @return an immutable list, empty when {@code values} is {@code null} or empty
     */
    static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    /**
     * Returns an unmodifiable copy of {@code values}, preserving iteration order.
     *
     * @param values the caller-owned map, possibly {@code null}
     * @param <K> the key type
     * @param <V> the value type
     * @return an immutable map, empty when {@code values} is {@code null} or empty
     */
    static <K, V> Map<K, V> immutableCopy(Map<K, V> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
