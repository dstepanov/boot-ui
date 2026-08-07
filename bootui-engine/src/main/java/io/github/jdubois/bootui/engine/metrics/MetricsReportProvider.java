package io.github.jdubois.bootui.engine.metrics;

import io.github.jdubois.bootui.core.dto.MetricAvailableTagDto;
import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricMeasurementDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
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

    private static final Comparator<String> NULL_SAFE_STRING = Comparator.nullsFirst(Comparator.naturalOrder());

    private static final List<String> VALID_TYPES = java.util.Arrays.stream(Meter.Type.values())
            .map(Enum::name)
            .sorted()
            .toList();

    private static final Comparator<Meter> SAMPLE_ORDER =
            (left, right) -> compareTags(left.getId().getTags(), right.getId().getTags());

    private final Supplier<MeterRegistry> registrySupplier;

    private final Predicate<Meter> meterFilter;

    public MetricsReportProvider(Supplier<MeterRegistry> registrySupplier, Predicate<Meter> meterFilter) {
        this.registrySupplier = registrySupplier;
        this.meterFilter = meterFilter;
    }

    public MetricsReport metrics() {
        return metrics(null, null, null, null);
    }

    public MetricsReport metrics(String query, String type, String offset, String limit) {
        int requestedOffset = parseOffset(offset);
        int requestedLimit = parseLimit(limit, DEFAULT_METER_LIMIT);
        String normalizedQuery = normalize(query);
        String normalizedType = parseType(type);

        MeterRegistry registry = registry();
        if (registry == null) {
            return emptyReport(false, requestedLimit);
        }

        Map<String, List<Meter>> metersByName = metersByName(visibleMeters(registry.getMeters()));
        TreeSet<String> availableTypes = new TreeSet<>();
        List<Map.Entry<String, List<Meter>>> matching = new ArrayList<>();
        for (Map.Entry<String, List<Meter>> entry : metersByName.entrySet()) {
            String meterType = firstType(entry.getValue());
            if (meterType != null) {
                availableTypes.add(meterType);
            }
            if (matches(
                    entry.getKey(), firstDescription(entry.getValue()), meterType, normalizedQuery, normalizedType)) {
                matching.add(entry);
            }
        }

        int fromIndex = Math.min(requestedOffset, matching.size());
        int toIndex = Math.min(fromIndex + requestedLimit, matching.size());
        List<MetricMeterDto> meters = matching.subList(fromIndex, toIndex).stream()
                .map(entry -> toMeterDto(entry.getKey(), entry.getValue()))
                .toList();
        PageMetadata page = new PageMetadata(
                metersByName.size(),
                matching.size(),
                fromIndex,
                requestedLimit,
                meters.size(),
                toIndex < matching.size());
        return new MetricsReport(true, metersByName.size(), meters, List.copyOf(availableTypes), page);
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

        return new MetricDetailDto(
                true,
                normalizedName,
                firstDescription(meters),
                firstBaseUnit(meters),
                firstType(meters),
                aggregateMeasurements(matchingMeters),
                availableTags(meters),
                samples,
                matchingMeters.size(),
                samplePage,
                fromIndex > 0 || toIndex < matchingMeters.size());
    }

    private MeterRegistry registry() {
        return registrySupplier.get();
    }

    private MetricsReport emptyReport(boolean metricsAvailable, int limit) {
        return new MetricsReport(metricsAvailable, 0, List.of(), List.of(), new PageMetadata(0, 0, 0, limit, 0, false));
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
            String name, String description, String type, String normalizedQuery, String normalizedType) {
        boolean matchesQuery = normalizedQuery.isEmpty()
                || normalize(name).contains(normalizedQuery)
                || normalize(description).contains(normalizedQuery);
        return matchesQuery && (normalizedType.isEmpty() || normalizedType.equals(type));
    }

    private MetricMeterDto toMeterDto(String name, List<Meter> meters) {
        return new MetricMeterDto(
                name, firstDescription(meters), firstBaseUnit(meters), firstType(meters), availableTags(meters));
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
                false);
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
