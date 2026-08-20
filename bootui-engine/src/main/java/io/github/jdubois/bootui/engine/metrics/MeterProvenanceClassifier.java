package io.github.jdubois.bootui.engine.metrics;

import io.github.jdubois.bootui.core.dto.MetricProvenanceDto;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Deterministic meter-name classifier over the curated {@link MeterFamilyCatalogue}.
 *
 * <p>Classification uses the meter <b>name</b> only. Tag values, current measurements, stack traces and
 * classpath presence never take part, so provenance cannot leak a tag value or change because a value moved.
 * A name that matches no curated family is reported honestly as unclassified rather than being attached to
 * the closest-looking integration.</p>
 *
 * <p>Ties are resolved in a fixed order so the same registry always produces the same DTOs:</p>
 *
 * <ol>
 *   <li>an exact-name pattern beats a prefix pattern;</li>
 *   <li>the longest pattern wins, so {@code http.server.requests} beats {@code http.server};</li>
 *   <li>remaining ties fall back to the family id, alphabetically.</li>
 * </ol>
 *
 * <p>Evaluation is bounded: patterns are a small fixed catalogue, and results are memoized per meter name in a
 * capped cache so a high-cardinality registry cannot grow BootUI's own memory without limit.</p>
 */
public final class MeterProvenanceClassifier {

    /** Explanation taken from the registry's own meter description. */
    public static final String SOURCE_NATIVE = "NATIVE";

    /** Explanation taken from BootUI's curated catalogue. */
    public static final String SOURCE_CURATED = "CURATED";

    /** No description is available and BootUI does not invent one. */
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    static final int MAX_CACHED_NAMES = 4096;

    private static final int MAX_CLASSIFIABLE_NAME_LENGTH = 512;

    private final Map<String, MeterFamily> cache = new ConcurrentHashMap<>();

    private final Map<String, MeterFamily> exactPatterns;

    private final Map<String, MeterFamily> prefixPatterns;

    public MeterProvenanceClassifier() {
        Map<String, MeterFamily> exact = new HashMap<>();
        Map<String, MeterFamily> prefixes = new HashMap<>();
        for (MeterFamily family : MeterFamilyCatalogue.families()) {
            for (String name : family.exactNames()) {
                exact.merge(name, family, MeterProvenanceClassifier::preferStableFamily);
            }
            for (String prefix : family.prefixes()) {
                prefixes.merge(prefix, family, MeterProvenanceClassifier::preferStableFamily);
            }
        }
        this.exactPatterns = Map.copyOf(exact);
        this.prefixPatterns = Map.copyOf(prefixes);
    }

    /** The curated family owning this meter name, or {@code null} when the name is unclassified. */
    public MeterFamily classify(String meterName) {
        if (meterName == null || meterName.isBlank() || meterName.length() > MAX_CLASSIFIABLE_NAME_LENGTH) {
            return null;
        }
        MeterFamily cached = cache.get(meterName);
        if (cached != null) {
            return cached == UnclassifiedMarker.FAMILY ? null : cached;
        }
        MeterFamily resolved = resolve(meterName.trim().toLowerCase(Locale.ROOT));
        if (cache.size() < MAX_CACHED_NAMES) {
            cache.put(meterName, resolved == null ? UnclassifiedMarker.FAMILY : resolved);
        }
        return resolved;
    }

    /** Number of memoized names, so tests can prove the cache stops growing. */
    int cacheSize() {
        return cache.size();
    }

    /**
     * Provenance for one meter name, preferring the registry's native description over the curated summary.
     *
     * @param meterName the meter name; never a tag value
     * @param nativeDescription the registry's own description, or {@code null}/blank when it registered none
     */
    public MetricProvenanceDto provenance(String meterName, String nativeDescription) {
        MeterFamily family = classify(meterName);
        MeterGroup group =
                family == null ? MeterFamilyCatalogue.applicationGroup() : MeterFamilyCatalogue.groupOf(family);
        boolean hasNativeDescription = nativeDescription != null && !nativeDescription.isBlank();

        String explanation;
        String source;
        if (hasNativeDescription) {
            explanation = nativeDescription.trim();
            source = SOURCE_NATIVE;
        } else if (family != null) {
            explanation = family.summary();
            source = SOURCE_CURATED;
        } else {
            explanation = null;
            source = SOURCE_UNKNOWN;
        }

        String interpretation = null;
        if (family != null) {
            interpretation =
                    family.interpretation() == null || family.interpretation().isBlank()
                            ? group.interpretation()
                            : family.interpretation();
        }

        return new MetricProvenanceDto(
                group.id(),
                group.label(),
                group.contributor(),
                family == null ? null : family.id(),
                family == null ? null : family.label(),
                family != null,
                explanation,
                source,
                interpretation);
    }

    /**
     * Resolves a normalized meter name by walking its dot segments from the longest to the shortest, so the most
     * specific curated prefix always wins and the work is proportional to the name's segment count rather than to
     * the size of the catalogue.
     */
    private MeterFamily resolve(String normalizedName) {
        MeterFamily exact = exactPatterns.get(normalizedName);
        if (exact != null) {
            return exact;
        }

        int end = normalizedName.length();
        while (end > 0) {
            MeterFamily candidate = prefixPatterns.get(normalizedName.substring(0, end));
            if (candidate != null) {
                return candidate;
            }
            end = normalizedName.lastIndexOf('.', end - 1);
        }
        return null;
    }

    private static MeterFamily preferStableFamily(MeterFamily left, MeterFamily right) {
        return left.id().compareTo(right.id()) <= 0 ? left : right;
    }

    /** Sentinel cached for names that are known to be unclassified, so they are not re-evaluated. */
    private static final class UnclassifiedMarker {

        private static final MeterFamily FAMILY =
                new MeterFamily("", "", MeterFamilyCatalogue.APPLICATION_GROUP_ID, null, null, "", null);

        private UnclassifiedMarker() {}
    }
}
