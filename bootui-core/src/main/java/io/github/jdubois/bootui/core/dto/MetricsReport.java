package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * Browseable list of Micrometer meters.
 */
public record MetricsReport(
        boolean metricsAvailable,
        int total,
        List<MetricMeterDto> meters,
        List<String> availableTypes,
        PageMetadata page) {

    public MetricsReport(boolean metricsAvailable, int total, List<MetricMeterDto> meters) {
        this(
                metricsAvailable,
                total,
                meters,
                meters.stream()
                        .map(MetricMeterDto::type)
                        .filter(type -> type != null && !type.isBlank())
                        .distinct()
                        .sorted()
                        .toList(),
                new PageMetadata(total, total, 0, meters.size(), meters.size(), meters.size() < total));
    }
}
