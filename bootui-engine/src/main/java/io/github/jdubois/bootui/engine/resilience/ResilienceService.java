package io.github.jdubois.bootui.engine.resilience;

import io.github.jdubois.bootui.core.dto.ResilienceEventDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResilienceReport;
import io.github.jdubois.bootui.spi.ResiliencePolicyProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral logic behind the Resilience panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads already-mapped policies from every available {@link ResiliencePolicyProvider} (Resilience4j
 * and Spring Retry on Spring, SmallRye Fault Tolerance on Quarkus) and the bounded, metadata-only capture
 * buffer, then applies BootUI's neutral concerns on top: stable ordering, hard caps, per-type aggregation,
 * and the availability wrapping. It imports no resilience library type at all, so an application with no
 * resilience library never links one through this class.</p>
 *
 * <p>Reading the report performs no protected call, network request, state transition or policy mutation:
 * providers only read native registries and captured annotation metadata. A provider that throws is
 * degraded into a warning rather than failing the whole panel, so one misbehaving library cannot hide the
 * others.</p>
 */
public final class ResilienceService {

    /** Hard cap on the number of policies rendered, so a huge registry cannot produce an unbounded page. */
    public static final int MAX_POLICIES = 500;

    private static final String UNAVAILABLE_REASON =
            "No supported resilience library is present (Resilience4j, Spring Retry or SmallRye Fault Tolerance)";

    private final List<ResiliencePolicyProvider> providers;
    private final ResilienceEventRecorder recorder;
    private final int maxEvents;

    /**
     * @param providers every candidate provider; {@code null} entries and unavailable providers are ignored
     * @param recorder the bounded capture buffer, or {@code null} when no capture backend is wired
     * @param maxEvents how many captured events the report returns, capped by the recorder's own buffer
     */
    public ResilienceService(
            List<ResiliencePolicyProvider> providers, ResilienceEventRecorder recorder, int maxEvents) {
        // Copied defensively without List.copyOf, which rejects the null entries an adapter's optional
        // bean wiring can legitimately contribute.
        this.providers = providers == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(providers));
        this.recorder = recorder;
        this.maxEvents = Math.max(1, maxEvents);
    }

    /** The ordered, bounded resilience report; unavailable when no supported library reports. */
    public ResilienceReport report() {
        List<String> providerIds = new ArrayList<>();
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (ResiliencePolicyProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            String providerId = provider.providerId();
            try {
                if (!provider.available()) {
                    continue;
                }
                List<ResiliencePolicyDto> reported = provider.policies();
                providerIds.add(providerId);
                if (reported != null) {
                    for (ResiliencePolicyDto policy : reported) {
                        if (policy != null) {
                            policies.add(policy);
                        }
                    }
                }
            } catch (RuntimeException | LinkageError ex) {
                // One library failing (a partially initialized registry, a shaded/absent optional class)
                // must never hide the libraries that do report.
                warnings.add("Could not read " + providerId + " policies: "
                        + ex.getClass().getSimpleName());
            }
        }

        int effectiveMaxEvents = recorder == null ? maxEvents : Math.min(maxEvents, recorder.getMaxEntries());

        if (providerIds.isEmpty() && policies.isEmpty()) {
            ResilienceReport unavailable = ResilienceReport.unavailable(UNAVAILABLE_REASON, effectiveMaxEvents);
            return warnings.isEmpty()
                    ? unavailable
                    : new ResilienceReport(
                            false,
                            UNAVAILABLE_REASON,
                            false,
                            List.of(),
                            0,
                            List.of(),
                            Map.of(),
                            List.of(),
                            effectiveMaxEvents,
                            List.copyOf(warnings));
        }

        policies.sort(
                Comparator.comparingInt((ResiliencePolicyDto policy) -> ResilienceVocabulary.typeRank(policy.type()))
                        .thenComparing(ResiliencePolicyDto::provider, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResiliencePolicyDto::name, Comparator.nullsLast(String::compareTo))
                        .thenComparing(ResiliencePolicyDto::target, Comparator.nullsLast(String::compareTo)));

        int total = policies.size();

        // Counted over every discovered policy, before the display cap, so the per-type summary stays
        // truthful even when the rendered list is truncated. LinkedHashMap keeps the contract's type order.
        Map<String, Integer> countsByType = new LinkedHashMap<>();
        for (ResiliencePolicyDto policy : policies) {
            countsByType.merge(policy.type() == null ? "UNKNOWN" : policy.type(), 1, Integer::sum);
        }

        if (policies.size() > MAX_POLICIES) {
            warnings.add("Showing the first " + MAX_POLICIES + " of " + total + " policies");
            policies = new ArrayList<>(policies.subList(0, MAX_POLICIES));
        }

        boolean captureEnabled = recorder != null && recorder.isEnabled();
        List<ResilienceEventDto> events = recorder == null ? List.of() : recorder.recentDtos(effectiveMaxEvents);

        return new ResilienceReport(
                true,
                null,
                captureEnabled,
                List.copyOf(providerIds),
                total,
                List.copyOf(policies),
                Collections.unmodifiableMap(countsByType),
                events,
                effectiveMaxEvents,
                List.copyOf(warnings));
    }
}
