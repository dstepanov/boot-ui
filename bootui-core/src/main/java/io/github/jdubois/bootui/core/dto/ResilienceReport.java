package io.github.jdubois.bootui.core.dto;

import java.util.List;
import java.util.Map;

/**
 * The Resilience panel's report: every discovered policy plus the recently captured, metadata-only
 * resilience events.
 *
 * <p>The panel is read-only. Rendering it performs no protected call, network request, state transition or
 * policy mutation — it reads native registries and the bounded capture buffer only.</p>
 *
 * @param resiliencePresent whether at least one supported resilience library is present and reporting
 * @param unavailableReason framework-correct explanation when {@code resiliencePresent} is {@code false},
 *     otherwise {@code null}
 * @param captureEnabled whether metadata-only event capture is currently active
 * @param providers ids of the reporting libraries, in stable order (for example
 *     {@code ["resilience4j", "spring-retry"]})
 * @param totalPolicies number of discovered policies
 * @param policies discovered policies in stable order (type, then provider, then name)
 * @param policyCountsByType policy counts keyed by neutral policy type, in the contract's type order
 * @param events captured events, newest first, capped by the configured buffer size
 * @param maxEvents the capture buffer's configured capacity
 * @param warnings honest, already-safe explanations for anything the adapter could not report
 */
public record ResilienceReport(
        boolean resiliencePresent,
        String unavailableReason,
        boolean captureEnabled,
        List<String> providers,
        int totalPolicies,
        List<ResiliencePolicyDto> policies,
        Map<String, Integer> policyCountsByType,
        List<ResilienceEventDto> events,
        int maxEvents,
        List<String> warnings) {

    /** An empty report explaining why no supported resilience library is reporting. */
    public static ResilienceReport unavailable(String reason, int maxEvents) {
        return new ResilienceReport(
                false, reason, false, List.of(), 0, List.of(), Map.of(), List.of(), maxEvents, List.of());
    }
}
