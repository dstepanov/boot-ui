package io.github.jdubois.bootui.engine.metrics;

import io.github.jdubois.bootui.core.dto.MetricAvailableTagDto;
import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricGroupDto;
import io.github.jdubois.bootui.core.dto.MetricMeasurementDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricProvenanceDto;
import io.github.jdubois.bootui.core.dto.MetricSampleDto;
import io.github.jdubois.bootui.core.dto.MetricTagDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.core.dto.PageMetadata;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Statistic;
import io.micrometer.core.instrument.Tag;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Framework-neutral Micrometer reporting service backing the Metrics panel.
 *
 * <p>The host adapter supplies two seams: a {@link Supplier} that resolves the live
 * {@link MeterRegistry} (or {@code null} when none is available, e.g. metrics disabled) on every
 * call, and a {@link Predicate} that decides whether a given {@link Meter} should be visible (the
 * Spring adapter feeds BootUI's own self-data filter so the console never reports its own traffic).
 * Micrometer is intentionally a direct dependency: it is the framework-neutral metrics API used by
 * both Spring Boot and Quarkus, so no extra abstraction is warranted.
 */
public class MetricsReportProvider {

    public static final int DEFAULT_METER_LIMIT = 200;

    public static final int DEFAULT_SAMPLE_LIMIT = 100;

    public static final int MAX_LIMIT = 1000;

    private static final int MAX_TAG_VALUES = 100;

    static final int MAX_COMMON_TAG_KEYS = 8;

    private static final int MAX_TRACKED_TAG_KEYS = 256;

    /** Upper bound on series inspected per meter name when deriving common tag keys for a group summary. */
    private static final int MAX_TAG_KEY_SERIES_PER_METER = 64;

    static final int MAX_GROUP_BASE_UNITS = 6;

    static final int MAX_GROUP_FAMILIES = 12;

    private static final String CLASSIFIED = "classified";

    private static final String UNCLASSIFIED = "unclassified";

    private static final List<String> VALID_PROVENANCE = List.of(CLASSIFIED, UNCLASSIFIED);

    private static final List<String> VALID_EXPLANATION_SOURCES = List.of(
            MeterProvenanceClassifier.SOURCE_CURATED,
            MeterProvenanceClassifier.SOURCE_NATIVE,
            MeterProvenanceClassifier.SOURCE_UNKNOWN);

    private static final Comparator<String> NULL_SAFE_STRING = Comparator.nullsFirst(Comparator.naturalOrder());

    private static final List<String> VALID_TYPES = java.util.Arrays.stream(Meter.Type.values())
            .map(Enum::name)
            .sorted()
            .toList();

    private static final Comparator<Meter> SAMPLE_ORDER =
            (left, right) -> compareTags(left.getId().getTags(), right.getId().getTags());

    private final Supplier<MeterRegistry> registrySupplier;

    private final Predicate<Meter> meterFilter;

    private final MeterProvenanceClassifier classifier = new MeterProvenanceClassifier();

    public MetricsReportProvider(Supplier<MeterRegistry> registrySupplier, Predicate<Meter> meterFilter) {
        this.registrySupplier = registrySupplier;
        this.meterFilter = meterFilter;
    }

    public MetricsReport metrics() {
        return metrics(null, null, null, null);
    }

    public MetricsReport metrics(String query, String type, String offset, String limit) {
        return metrics(query, type, null, null, null, offset, limit);
    }

    /**
     * Bounded meter list with provenance groups.
     *
     * <p>{@code group}, {@code provenance} and {@code explanation} narrow the list by curated provenance group,
     * by whether the meter is classified at all, and by where its explanation comes from. The returned groups
     * are aggregated over the meters matching every filter <em>except</em> {@code group}, so the group list
     * stays navigable while the selected group's count equals {@code page.matched}.</p>
     */
    public MetricsReport metrics(
            String query,
            String type,
            String group,
            String provenance,
            String explanation,
            String offset,
            String limit) {
        int requestedOffset = parseOffset(offset);
        int requestedLimit = parseLimit(limit, DEFAULT_METER_LIMIT);
        String normalizedQuery = normalize(query);
        String normalizedType = parseType(type);
        String normalizedGroup = parseGroup(group);
        String normalizedProvenance = parseProvenance(provenance);
        String normalizedExplanation = parseExplanation(explanation);

        MeterRegistry registry = registry();
        if (registry == null) {
            return emptyReport(false, requestedLimit);
        }

        Map<String, List<Meter>> metersByName = metersByName(visibleMeters(registry.getMeters()));
        TreeSet<String> availableTypes = new TreeSet<>();
        List<MeterEntry> beforeGroupFilter = new ArrayList<>();
        for (Map.Entry<String, List<Meter>> entry : metersByName.entrySet()) {
            MeterEntry meterEntry = toEntry(entry.getKey(), entry.getValue());
            if (meterEntry.type() != null) {
                availableTypes.add(meterEntry.type());
            }
            if (matches(meterEntry, normalizedQuery, normalizedType, normalizedProvenance, normalizedExplanation)) {
                beforeGroupFilter.add(meterEntry);
            }
        }

        List<MetricGroupDto> groups = toGroups(beforeGroupFilter);
        List<MeterEntry> matching = normalizedGroup.isEmpty()
                ? beforeGroupFilter
                : beforeGroupFilter.stream()
                        .filter(entry ->
                                normalizedGroup.equals(entry.provenance().groupId()))
                        .toList();

        int fromIndex = Math.min(requestedOffset, matching.size());
        int toIndex = Math.min(fromIndex + requestedLimit, matching.size());
        List<MetricMeterDto> meters = matching.subList(fromIndex, toIndex).stream()
                .map(this::toMeterDto)
                .toList();
        PageMetadata page = new PageMetadata(
                metersByName.size(),
                matching.size(),
                fromIndex,
                requestedLimit,
                meters.size(),
                toIndex < matching.size());
        return new MetricsReport(
                true,
                metersByName.size(),
                meters,
                List.copyOf(availableTypes),
                page,
                groups,
                MeterFamilyCatalogue.VERSION);
    }

    public MetricDetailDto metric(String name, List<String> tagFilters) {
        return metric(name, tagFilters, null, null);
    }

    public MetricDetailDto metric(String name, List<String> tagFilters, String offset, String limit) {
        String normalizedName = requireName(name);
        Map<String, String> requiredTags = parseTagFilters(tagFilters);
        int requestedOffset = parseOffset(offset);
        int requestedLimit = parseLimit(limit, DEFAULT_SAMPLE_LIMIT);

        MeterRegistry registry = registry();
        if (registry == null) {
            return emptyDetail(false, normalizedName, requestedLimit);
        }

        List<Meter> meters = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals(normalizedName))
                .filter(meterFilter)
                .sorted(SAMPLE_ORDER)
                .toList();
        if (meters.isEmpty()) {
            return emptyDetail(true, normalizedName, requestedLimit);
        }

        List<Meter> matchingMeters =
                meters.stream().filter(meter -> hasTags(meter, requiredTags)).toList();
        int fromIndex = Math.min(requestedOffset, matchingMeters.size());
        int toIndex = Math.min(fromIndex + requestedLimit, matchingMeters.size());
        List<MetricSampleDto> samples = matchingMeters.subList(fromIndex, toIndex).stream()
                .map(this::toSample)
                .toList();
        PageMetadata samplePage = new PageMetadata(
                matchingMeters.size(),
                matchingMeters.size(),
                fromIndex,
                requestedLimit,
                samples.size(),
                toIndex < matchingMeters.size());

        String description = firstDescription(meters);
        return new MetricDetailDto(
                true,
                normalizedName,
                description,
                firstBaseUnit(meters),
                firstType(meters),
                aggregateMeasurements(matchingMeters),
                availableTags(meters),
                samples,
                matchingMeters.size(),
                samplePage,
                fromIndex > 0 || toIndex < matchingMeters.size(),
                classifier.provenance(normalizedName, description));
    }

    private MeterRegistry registry() {
        return registrySupplier.get();
    }

    private MetricsReport emptyReport(boolean metricsAvailable, int limit) {
        return new MetricsReport(
                metricsAvailable,
                0,
                List.of(),
                List.of(),
                new PageMetadata(0, 0, 0, limit, 0, false),
                List.of(),
                MeterFamilyCatalogue.VERSION);
    }

    private Map<String, List<Meter>> metersByName(List<Meter> meters) {
        Map<String, List<Meter>> grouped = new TreeMap<>();
        for (Meter meter : meters) {
            grouped.computeIfAbsent(meter.getId().getName(), name -> new ArrayList<>())
                    .add(meter);
        }
        return grouped;
    }

    private List<Meter> visibleMeters(List<Meter> meters) {
        return meters.stream().filter(meterFilter).toList();
    }

    private boolean matches(
            MeterEntry entry,
            String normalizedQuery,
            String normalizedType,
            String normalizedProvenance,
            String normalizedExplanation) {
        boolean matchesQuery = normalizedQuery.isEmpty()
                || normalize(entry.name()).contains(normalizedQuery)
                || normalize(entry.description()).contains(normalizedQuery);
        if (!matchesQuery) {
            return false;
        }
        if (!normalizedType.isEmpty() && !normalizedType.equals(entry.type())) {
            return false;
        }
        if (!normalizedProvenance.isEmpty()
                && !normalizedProvenance.equals(entry.provenance().classified() ? CLASSIFIED : UNCLASSIFIED)) {
            return false;
        }
        return normalizedExplanation.isEmpty()
                || normalizedExplanation.equals(entry.provenance().explanationSource());
    }

    private MeterEntry toEntry(String name, List<Meter> meters) {
        String description = firstDescription(meters);
        return new MeterEntry(
                name,
                meters,
                description,
                firstBaseUnit(meters),
                firstType(meters),
                classifier.provenance(name, description));
    }

    private MetricMeterDto toMeterDto(MeterEntry entry) {
        return new MetricMeterDto(
                entry.name(),
                entry.description(),
                entry.baseUnit(),
                entry.type(),
                availableTags(entry.meters()),
                entry.provenance());
    }

    private List<MetricGroupDto> toGroups(List<MeterEntry> entries) {
        Map<String, GroupAccumulator> accumulators = new LinkedHashMap<>();
        for (MeterEntry entry : entries) {
            accumulators
                    .computeIfAbsent(entry.provenance().groupId(), groupId -> new GroupAccumulator())
                    .add(entry);
        }

        List<MetricGroupDto> groups = new ArrayList<>();
        Map<String, MeterGroup> groupsById = MeterFamilyCatalogue.groupsById();
        for (Map.Entry<String, GroupAccumulator> entry : accumulators.entrySet()) {
            MeterGroup group = groupsById.getOrDefault(entry.getKey(), MeterFamilyCatalogue.applicationGroup());
            groups.add(entry.getValue().toDto(group));
        }
        groups.sort(Comparator.comparingInt(group -> MeterFamilyCatalogue.groupOrder(group.id())));
        return List.copyOf(groups);
    }

    private MetricDetailDto emptyDetail(boolean metricsAvailable, String name, int limit) {
        return new MetricDetailDto(
                metricsAvailable,
                name,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0,
                new PageMetadata(0, 0, 0, limit, 0, false),
                false,
                // Provenance is derived from the meter name alone, so an unknown name is still described honestly as
                // long as a registry is present. Without a registry there is nothing to explain.
                metricsAvailable ? classifier.provenance(name, null) : null);
    }

    private String firstDescription(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getDescription())
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private String firstBaseUnit(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getBaseUnit())
                .filter(value -> value != null && !value.isBlank())
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private String firstType(List<Meter> meters) {
        return meters.stream()
                .map(meter -> meter.getId().getType())
                .filter(type -> type != null)
                .map(Enum::name)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Metric name must not be blank");
        }
        return name.trim();
    }

    private int parseOffset(String value) {
        if (value == null) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Render the same stable validation error on every adapter.
        }
        throw new IllegalArgumentException("Metric offset must be 0 or greater");
    }

    private int parseLimit(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed >= 1 && parsed <= MAX_LIMIT) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // Render the same stable validation error on every adapter.
        }
        throw new IllegalArgumentException("Metric limit must be between 1 and " + MAX_LIMIT);
    }

    private String parseType(String type) {
        String normalized = normalize(type).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        if (!VALID_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Metric type must be one of: " + String.join(", ", VALID_TYPES));
        }
        return normalized;
    }

    private String parseGroup(String group) {
        String normalized = normalize(group);
        if (normalized.isEmpty()) {
            return "";
        }
        List<String> validGroups =
                MeterFamilyCatalogue.groupsById().keySet().stream().sorted().toList();
        if (!validGroups.contains(normalized)) {
            throw new IllegalArgumentException("Metric group must be one of: " + String.join(", ", validGroups));
        }
        return normalized;
    }

    private String parseProvenance(String provenance) {
        String normalized = normalize(provenance);
        if (normalized.isEmpty()) {
            return "";
        }
        if (!VALID_PROVENANCE.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Metric provenance must be one of: " + String.join(", ", VALID_PROVENANCE));
        }
        return normalized;
    }

    private String parseExplanation(String explanation) {
        String normalized = normalize(explanation).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return "";
        }
        if (!VALID_EXPLANATION_SOURCES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Metric explanation source must be one of: " + String.join(", ", VALID_EXPLANATION_SOURCES));
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, String> parseTagFilters(List<String> tagFilters) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagFilters == null) {
            return tags;
        }
        for (String tagFilter : tagFilters) {
            int separator = tagFilter == null ? -1 : tagFilter.indexOf(':');
            if (separator <= 0 || tagFilter.substring(0, separator).isBlank()) {
                throw new IllegalArgumentException("Metric tag filters must use key:value syntax");
            }
            tags.put(tagFilter.substring(0, separator), tagFilter.substring(separator + 1));
        }
        return tags;
    }

    private boolean hasTags(Meter meter, Map<String, String> requiredTags) {
        for (Map.Entry<String, String> requiredTag : requiredTags.entrySet()) {
            if (!requiredTag.getValue().equals(meter.getId().getTag(requiredTag.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private List<MetricAvailableTagDto> availableTags(List<Meter> meters) {
        Map<String, BoundedTagValues> valuesByKey = new TreeMap<>();
        for (Meter meter : meters) {
            for (Tag tag : meter.getId().getTags()) {
                valuesByKey
                        .computeIfAbsent(tag.getKey(), key -> new BoundedTagValues())
                        .add(tag.getValue());
            }
        }

        List<MetricAvailableTagDto> tags = new ArrayList<>();
        for (Map.Entry<String, BoundedTagValues> entry : valuesByKey.entrySet()) {
            tags.add(new MetricAvailableTagDto(
                    entry.getKey(), List.copyOf(entry.getValue().values), entry.getValue().truncated));
        }
        return tags;
    }

    private MetricSampleDto toSample(Meter meter) {
        List<MetricTagDto> tags = meter.getId().getTags().stream()
                .sorted(Comparator.comparing(Tag::getKey, NULL_SAFE_STRING)
                        .thenComparing(Tag::getValue, NULL_SAFE_STRING))
                .map(tag -> new MetricTagDto(tag.getKey(), tag.getValue()))
                .toList();
        return new MetricSampleDto(tags, measurements(meter));
    }

    private List<MetricMeasurementDto> aggregateMeasurements(List<Meter> meters) {
        Map<Statistic, Double> valuesByStatistic = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Meter meter : meters) {
            for (Measurement measurement : meter.measure()) {
                double value = measurement.getValue();
                if (!Double.isFinite(value)) {
                    continue;
                }
                Statistic statistic = measurement.getStatistic();
                valuesByStatistic.merge(statistic, value, statistic == Statistic.MAX ? Math::max : Double::sum);
            }
        }
        return toMeasurements(valuesByStatistic);
    }

    private List<MetricMeasurementDto> measurements(Meter meter) {
        Map<Statistic, Double> valuesByStatistic = new TreeMap<>(Comparator.comparing(Enum::name));
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) {
                valuesByStatistic.put(measurement.getStatistic(), value);
            }
        }
        return toMeasurements(valuesByStatistic);
    }

    private List<MetricMeasurementDto> toMeasurements(Map<Statistic, Double> valuesByStatistic) {
        List<MetricMeasurementDto> measurements = new ArrayList<>();
        for (Map.Entry<Statistic, Double> entry : valuesByStatistic.entrySet()) {
            measurements.add(new MetricMeasurementDto(entry.getKey().getTagValueRepresentation(), entry.getValue()));
        }
        return measurements;
    }

    private static int compareTags(List<Tag> left, List<Tag> right) {
        List<Tag> sortedLeft = left.stream()
                .sorted(Comparator.comparing(Tag::getKey, NULL_SAFE_STRING)
                        .thenComparing(Tag::getValue, NULL_SAFE_STRING))
                .toList();
        List<Tag> sortedRight = right.stream()
                .sorted(Comparator.comparing(Tag::getKey, NULL_SAFE_STRING)
                        .thenComparing(Tag::getValue, NULL_SAFE_STRING))
                .toList();
        int commonSize = Math.min(sortedLeft.size(), sortedRight.size());
        for (int index = 0; index < commonSize; index++) {
            int keyComparison = NULL_SAFE_STRING.compare(
                    sortedLeft.get(index).getKey(), sortedRight.get(index).getKey());
            if (keyComparison != 0) {
                return keyComparison;
            }
            int valueComparison = NULL_SAFE_STRING.compare(
                    sortedLeft.get(index).getValue(), sortedRight.get(index).getValue());
            if (valueComparison != 0) {
                return valueComparison;
            }
        }
        return Integer.compare(sortedLeft.size(), sortedRight.size());
    }

    /** One meter name with its registry metadata and resolved provenance, shared by filtering and grouping. */
    private record MeterEntry(
            String name,
            List<Meter> meters,
            String description,
            String baseUnit,
            String type,
            MetricProvenanceDto provenance) {}

    /**
     * Bounded aggregation of one provenance group: meter and documentation counts, the curated families that
     * matched, the tag keys shared by most of the group's meters, and the native base units in use.
     *
     * <p>Only tag <em>keys</em> are collected. Tag values never reach a group summary, so a high-cardinality or
     * sensitive tag value cannot leak into an explanation, and the tracked key set itself is capped.</p>
     */
    private static final class GroupAccumulator {

        private final Map<String, Integer> tagKeyCounts = new TreeMap<>();

        private final TreeSet<String> families = new TreeSet<>();

        private final TreeSet<String> baseUnits = new TreeSet<>();

        private int meterCount;

        private int describedMeterCount;

        private void add(MeterEntry entry) {
            meterCount++;
            if (entry.description() != null && !entry.description().isBlank()) {
                describedMeterCount++;
            }
            if (entry.baseUnit() != null && !entry.baseUnit().isBlank()) {
                baseUnits.add(entry.baseUnit());
            }
            String familyLabel = entry.provenance().familyLabel();
            if (familyLabel != null && !familyLabel.isBlank() && families.size() < MAX_GROUP_FAMILIES) {
                families.add(familyLabel);
            }
            for (String tagKey : tagKeys(entry.meters())) {
                if (tagKeyCounts.containsKey(tagKey) || tagKeyCounts.size() < MAX_TRACKED_TAG_KEYS) {
                    tagKeyCounts.merge(tagKey, 1, Integer::sum);
                }
            }
        }

        private MetricGroupDto toDto(MeterGroup group) {
            return new MetricGroupDto(
                    group.id(),
                    group.label(),
                    group.contributor(),
                    group.summary(),
                    group.interpretation(),
                    meterCount,
                    describedMeterCount,
                    List.copyOf(families),
                    commonTagKeys(),
                    baseUnits.stream().limit(MAX_GROUP_BASE_UNITS).toList());
        }

        /** Tag keys carried by at least half of the group's meters, most common first, then alphabetical. */
        private List<String> commonTagKeys() {
            int threshold = Math.max(1, (meterCount + 1) / 2);
            return tagKeyCounts.entrySet().stream()
                    .filter(entry -> entry.getValue() >= threshold)
                    .sorted(Map.Entry.<String, Integer>comparingByValue()
                            .reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .limit(MAX_COMMON_TAG_KEYS)
                    .map(Map.Entry::getKey)
                    .toList();
        }

        private static TreeSet<String> tagKeys(List<Meter> meters) {
            TreeSet<String> keys = new TreeSet<>(NULL_SAFE_STRING);
            int inspected = 0;
            for (Meter meter : meters) {
                if (inspected++ >= MAX_TAG_KEY_SERIES_PER_METER) {
                    break;
                }
                for (Tag tag : meter.getId().getTags()) {
                    keys.add(tag.getKey());
                }
            }
            return keys;
        }
    }

    private static final class BoundedTagValues {
        private final TreeSet<String> values = new TreeSet<>(NULL_SAFE_STRING);

        private boolean truncated;

        private void add(String value) {
            if (values.contains(value)) {
                return;
            }
            if (values.size() < MAX_TAG_VALUES) {
                values.add(value);
                return;
            }
            truncated = true;
            if (NULL_SAFE_STRING.compare(value, values.last()) < 0) {
                values.add(value);
                values.pollLast();
            }
        }
    }
}
