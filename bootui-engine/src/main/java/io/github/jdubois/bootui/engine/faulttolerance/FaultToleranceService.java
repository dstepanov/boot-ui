package io.github.jdubois.bootui.engine.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultToleranceEventDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Framework-neutral logic behind the Fault Tolerance panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads already-mapped policies from every available {@link FaultTolerancePolicyProvider} (Resilience4j
 * and Spring Retry on Spring, SmallRye Fault Tolerance on Quarkus) and the bounded, metadata-only capture
 * buffer, then applies BootUI's neutral concerns on top: stable ordering, hard caps, per-type aggregation,
 * and the availability wrapping. It imports no fault tolerance library type at all, so an application with no
 * fault tolerance library never links one through this class.</p>
 *
 * <p>Reading the report performs no protected call, network request, state transition or policy mutation:
 * providers only read native registries and captured annotation metadata. A provider that throws is
 * degraded into a warning rather than failing the whole panel, so one misbehaving library cannot hide the
 * others.</p>
 */
public final class FaultToleranceService {

    /** Hard cap on the number of policies rendered, so a huge registry cannot produce an unbounded page. */
    public static final int MAX_POLICIES = 500;

    private static final String UNAVAILABLE_REASON =
            "No supported fault tolerance library is present (Resilience4j, Spring Retry or SmallRye Fault Tolerance)";

    private final List<FaultTolerancePolicyProvider> providers;
    private final FaultToleranceEventRecorder recorder;
    private final int maxEvents;

    /**
     * @param providers every candidate provider; {@code null} entries and unavailable providers are ignored
     * @param recorder the bounded capture buffer, or {@code null} when no capture backend is wired
     * @param maxEvents how many captured events the report returns, capped by the recorder's own buffer
     */
    public FaultToleranceService(
            List<FaultTolerancePolicyProvider> providers, FaultToleranceEventRecorder recorder, int maxEvents) {
        // Copied defensively without List.copyOf, which rejects the null entries an adapter's optional
        // bean wiring can legitimately contribute.
        this.providers = providers == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(providers));
        this.recorder = recorder;
        this.maxEvents = Math.max(1, maxEvents);
    }

    /** The ordered, bounded fault tolerance report; unavailable when no supported library reports. */
    public FaultToleranceReport report() {
        List<String> providerIds = new ArrayList<>();
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (FaultTolerancePolicyProvider provider : providers) {
            if (provider == null) {
                continue;
            }
            String providerId = provider.providerId();
            try {
                if (!provider.available()) {
                    continue;
                }
                List<FaultTolerancePolicyDto> reported = provider.policies();
                providerIds.add(providerId);
                if (reported != null) {
                    for (FaultTolerancePolicyDto policy : reported) {
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
            FaultToleranceReport unavailable = FaultToleranceReport.unavailable(UNAVAILABLE_REASON, effectiveMaxEvents);
            return warnings.isEmpty()
                    ? unavailable
                    : new FaultToleranceReport(
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

        policies.sort(Comparator.comparingInt(
                        (FaultTolerancePolicyDto policy) -> FaultToleranceVocabulary.typeRank(policy.type()))
                .thenComparing(FaultTolerancePolicyDto::provider, Comparator.nullsLast(String::compareTo))
                .thenComparing(FaultTolerancePolicyDto::name, Comparator.nullsLast(String::compareTo))
                .thenComparing(FaultTolerancePolicyDto::target, Comparator.nullsLast(String::compareTo)));

        int total = policies.size();

        // Counted over every discovered policy, before the display cap, so the per-type summary stays
        // truthful even when the rendered list is truncated. LinkedHashMap keeps the contract's type order.
        Map<String, Integer> countsByType = new LinkedHashMap<>();
        for (FaultTolerancePolicyDto policy : policies) {
            countsByType.merge(policy.type() == null ? "UNKNOWN" : policy.type(), 1, Integer::sum);
        }

        if (policies.size() > MAX_POLICIES) {
            warnings.add("Showing the first " + MAX_POLICIES + " of " + total + " policies");
            policies = new ArrayList<>(policies.subList(0, MAX_POLICIES));
        }

        boolean captureEnabled = recorder != null && recorder.isEnabled();
        List<FaultToleranceEventDto> events = recorder == null ? List.of() : recorder.recentDtos(effectiveMaxEvents);

        return new FaultToleranceReport(
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
