package io.github.jdubois.bootui.engine.metrics;

/**
 * One provenance group in the curated meter catalogue, such as {@code jvm} or {@code http-server}.
 *
 * <p>The group carries the integration that contributes its meter families ({@code contributor}) plus a short
 * static explanation of what the family measures and how to read its principal counters, gauges, timers and
 * distributions. All text is authored in {@link MeterFamilyCatalogue}; nothing is derived from live meter values
 * or tag values.</p>
 *
 * @param id stable identifier used by the {@code group} filter and by the browser contract
 * @param label human-readable group name
 * @param contributor the integration credited with the group's meters
 * @param summary what the group's meters measure
 * @param interpretation how to read the group's principal measurements
 */
public record MeterGroup(String id, String label, String contributor, String summary, String interpretation) {

    public MeterGroup {
        id = id == null ? "" : id;
        label = label == null ? "" : label;
        contributor = contributor == null ? "" : contributor;
        summary = summary == null ? "" : summary;
        interpretation = interpretation == null ? "" : interpretation;
    }
}
