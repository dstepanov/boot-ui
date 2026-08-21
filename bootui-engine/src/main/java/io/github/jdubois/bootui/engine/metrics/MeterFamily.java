package io.github.jdubois.bootui.engine.metrics;

import java.util.List;
import java.util.Locale;

/**
 * One curated meter family: a set of stable meter-name patterns that a known integration registers, plus the
 * explanation BootUI shows when the registry itself provides no description.
 *
 * <p>Two pattern kinds are supported, and both match on the meter <em>name</em> only — never on tag values:</p>
 *
 * <ul>
 *   <li>{@code exactNames} match a meter name literally. They are used where an integration owns individual
 *       names inside a namespace an application may also use (for example the Micrometer {@code cache.*}
 *       binder meters).</li>
 *   <li>{@code prefixes} match on dot-segment boundaries: {@code jvm.memory} matches {@code jvm.memory} and
 *       {@code jvm.memory.used}, but never {@code jvm.memorypressure}. They are used only where an integration
 *       owns the whole namespace.</li>
 * </ul>
 *
 * <p>Matching is case-insensitive on {@link Locale#ROOT} because Micrometer naming conventions are
 * lowercase-dotted, and ties are resolved deterministically by {@link MeterProvenanceClassifier}.</p>
 *
 * @param id stable family identifier, versioned with the catalogue
 * @param label human-readable family name, for example {@code HikariCP pool}
 * @param groupId the {@link MeterGroup} this family belongs to
 * @param exactNames literal meter names owned by the family
 * @param prefixes dot-segment prefixes owned by the family
 * @param summary what the family measures
 * @param interpretation how to read the family's principal measurements, or {@code null} to inherit the group's
 */
public record MeterFamily(
        String id,
        String label,
        String groupId,
        List<String> exactNames,
        List<String> prefixes,
        String summary,
        String interpretation) {

    public MeterFamily {
        exactNames = normalize(exactNames);
        prefixes = normalize(prefixes);
    }

    private static List<String> normalize(List<String> patterns) {
        return patterns == null
                ? List.of()
                : patterns.stream()
                        .filter(pattern -> pattern != null && !pattern.isBlank())
                        .map(pattern -> pattern.trim().toLowerCase(Locale.ROOT))
                        .distinct()
                        .toList();
    }
}
