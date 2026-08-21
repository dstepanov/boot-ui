package io.github.jdubois.bootui.engine.cache;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheClearResult;
import io.github.jdubois.bootui.core.dto.CacheDto;
import io.github.jdubois.bootui.core.dto.CacheManagerDto;
import io.github.jdubois.bootui.core.dto.CacheMetricsDto;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.core.dto.CacheTierDto;
import io.github.jdubois.bootui.spi.CacheManagerSnapshot;
import io.github.jdubois.bootui.spi.CacheOperationDiscovery;
import io.github.jdubois.bootui.spi.CacheProvider;
import io.github.jdubois.bootui.spi.CacheSnapshot;
import io.github.jdubois.bootui.spi.CacheTierSnapshot;
import io.micrometer.core.instrument.Measurement;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Framework-neutral logic behind the Cache panel, shared by the Spring Boot and Quarkus adapters.
 *
 * <p>It reads the host application's cache topology and performs eviction through a {@link CacheProvider}
 * (the framework-specific seam) while owning everything neutral: overlaying Micrometer cache metrics (read
 * live from the shared {@link MeterRegistry} with conventions identical on both frameworks), ordering
 * managers and caches, counting, and orchestrating the clear action (confirmation, routing, not-found
 * handling and {@link CacheClearResponse} shaping).</p>
 *
 * <p>The metric reading is fed the same way as {@code MetricsReportProvider}: the adapter supplies a
 * {@code Supplier<MeterRegistry>} (resolving its registry however it likes — {@code ObjectProvider} on
 * Spring, CDI {@code Instance} on Quarkus, possibly {@code null}) and a {@code Predicate<Meter>} self-filter
 * (Spring's {@code BootUiSelfDataFilter::shouldIncludeMeter} or the engine {@code MeterSelfFilter}), so the
 * Spring extraction is byte-identical and Quarkus reuses the same accumulation untouched.</p>
 */
public final class CacheService {

    /**
     * Report bounds. A cache manager can expose an unbounded number of caches (and a future provider an
     * unbounded number of tiers), so the report is capped before serialization and the truncation is made
     * visible in the report's warnings rather than silently dropping rows.
     */
    static final int MAX_MANAGERS = 100;

    static final int MAX_CACHES_PER_MANAGER = 500;

    static final int MAX_TIERS_PER_CACHE = 20;

    /**
     * Clear-all deliberately clears every cache the provider still hands back, including any the report
     * had to truncate — a partial clear would be a worse surprise than a complete one. The response's list
     * of names is capped all the same so the reply stays bounded; the count above it is always exact.
     */
    static final int MAX_CLEARED_NAMES = 200;

    private static final Logger log = System.getLogger(CacheService.class.getName());

    private final CacheProvider provider;

    private final Supplier<MeterRegistry> registry;

    private final Predicate<Meter> meterFilter;

    public CacheService(CacheProvider provider, Supplier<MeterRegistry> registry, Predicate<Meter> meterFilter) {
        this.provider = provider;
        this.registry = registry;
        this.meterFilter = meterFilter;
    }

    /** The cache report: managers, caches, tiers and overlaid metrics, plus discovered annotation operations. */
    public CacheReport report() {
        if (provider == null) {
            return new CacheReport(false, true, 0, 0, 0, 0, false, List.of(), List.of(), List.of());
        }
        CacheOperationDiscovery operations = provider.operations();
        boolean clearEnabled = provider.clearEnabled();
        if (!provider.available()) {
            return new CacheReport(
                    false,
                    clearEnabled,
                    0,
                    0,
                    operations.operations().size(),
                    0,
                    false,
                    List.of(),
                    operations.operations(),
                    operations.warnings());
        }

        List<CacheManagerSnapshot> managers = sortedManagers();
        List<String> warnings = new ArrayList<>(operations.warnings());
        boolean truncated = false;
        if (managers.size() > MAX_MANAGERS) {
            warnings.add("Cache report was truncated to the first " + MAX_MANAGERS + " of " + managers.size()
                    + " cache managers.");
            managers = managers.subList(0, MAX_MANAGERS);
            truncated = true;
        }

        Map<MetricKey, CacheMetricsAccumulator> metrics = cacheMetrics();
        Truncation truncation = new Truncation(truncated);
        List<CacheManagerDto> managerDtos = new ArrayList<>(managers.size());
        int tierCount = 0;
        for (CacheManagerSnapshot manager : managers) {
            CacheManagerDto dto = toManagerDto(manager, metrics, warnings, truncation);
            managerDtos.add(dto);
            for (CacheDto cache : dto.caches()) {
                tierCount += cache.tiers().size();
            }
        }
        int cacheCount = managerDtos.stream()
                .mapToInt(manager -> manager.caches().size())
                .sum();
        return new CacheReport(
                !managers.isEmpty(),
                clearEnabled,
                managers.size(),
                cacheCount,
                operations.operations().size(),
                tierCount,
                truncation.truncated,
                managerDtos,
                operations.operations(),
                List.copyOf(warnings));
    }

    /** Clears one cache or every known cache, owning all confirmation/routing/result orchestration. */
    public CacheClearResponse clear(CacheClearRequest request) {
        if (provider == null) {
            return result(409, "unavailable", "No cache backend is available.", List.of());
        }
        if (!provider.clearEnabled()) {
            return result(
                    409,
                    "disabled",
                    "Cache clearing is disabled. Set bootui.cache.clear-enabled=true to allow it.",
                    List.of());
        }
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            return result(400, "confirmation_required", "Cache clearing requires explicit confirmation.", List.of());
        }

        String unavailableReason = provider.clearUnavailableReason().orElse(null);
        if (unavailableReason != null) {
            return result(409, "unavailable", unavailableReason, List.of());
        }

        List<CacheManagerSnapshot> managers = sortedManagers();
        if (Boolean.TRUE.equals(request.all())) {
            return clearAll(managers);
        }
        if (isBlank(request.managerName()) || isBlank(request.cacheName())) {
            return result(
                    400,
                    "invalid_request",
                    "managerName and cacheName are required when clearing one cache.",
                    List.of());
        }
        return clearOne(managers, request.managerName(), request.cacheName());
    }

    private CacheClearResponse clearAll(List<CacheManagerSnapshot> managers) {
        List<String> cleared = new ArrayList<>();
        int clearedCount = 0;
        for (CacheManagerSnapshot manager : managers) {
            for (CacheSnapshot cache : sortedCaches(manager)) {
                boolean evicted;
                try {
                    evicted = provider.evict(manager.name(), cache.name());
                } catch (RuntimeException ex) {
                    return failedResult(manager.name(), cache.name(), ex, cleared);
                }
                if (evicted) {
                    clearedCount++;
                    if (cleared.size() < MAX_CLEARED_NAMES) {
                        cleared.add(manager.name() + "/" + cache.name());
                    }
                }
                // A cache the manager no longer hands back between the snapshot and the eviction is skipped,
                // matching the pre-extraction clearAll behavior (it would `continue` past a null getCache()).
            }
        }
        if (clearedCount > cleared.size()) {
            cleared.add("... and " + (clearedCount - cleared.size()) + " more");
        }
        log.log(Level.WARNING, "BootUI cleared " + clearedCount + " cache(s): " + cleared);
        return result(200, "cleared", "Cleared " + clearedCount + " cache" + (clearedCount == 1 ? "." : "s."), cleared);
    }

    private CacheClearResponse clearOne(List<CacheManagerSnapshot> managers, String managerName, String cacheName) {
        CacheManagerSnapshot manager = managers.stream()
                .filter(entry -> entry.name().equals(managerName))
                .findFirst()
                .orElse(null);
        if (manager == null) {
            return result(404, "not_found", "Cache manager '" + managerName + "' was not found.", List.of());
        }
        boolean known = manager.caches().stream().anyMatch(cache -> cache.name().equals(cacheName));
        if (!known) {
            return result(
                    404,
                    "not_found",
                    "Cache '" + cacheName + "' was not found in manager '" + managerName + "'.",
                    List.of());
        }

        boolean evicted;
        try {
            evicted = provider.evict(manager.name(), cacheName);
        } catch (RuntimeException ex) {
            return failedResult(manager.name(), cacheName, ex, List.of());
        }
        if (!evicted) {
            return result(
                    404,
                    "not_found",
                    "Cache '" + cacheName + "' was not returned by manager '" + managerName + "'.",
                    List.of());
        }
        List<String> cleared = List.of(manager.name() + "/" + cacheName);
        log.log(Level.WARNING, "BootUI cleared cache entry " + manager.name() + " / " + cacheName);
        return result(
                200, "cleared", "Cleared cache '" + cacheName + "' from manager '" + manager.name() + "'.", cleared);
    }

    private CacheClearResponse failedResult(
            String managerName, String cacheName, RuntimeException ex, List<String> cleared) {
        return result(
                500,
                "failed",
                "Failed to clear cache '" + cacheName + "' from manager '" + managerName + "' ("
                        + ex.getClass().getSimpleName() + ").",
                cleared);
    }

    private CacheClearResponse result(int status, String resultStatus, String message, List<String> caches) {
        return new CacheClearResponse(status, new CacheClearResult(resultStatus, message, caches.size(), caches));
    }

    private List<CacheManagerSnapshot> sortedManagers() {
        return provider.managers().stream()
                .sorted(Comparator.comparing(CacheManagerSnapshot::name))
                .toList();
    }

    private List<CacheSnapshot> sortedCaches(CacheManagerSnapshot manager) {
        return manager.caches().stream()
                .sorted(Comparator.comparing(CacheSnapshot::name))
                .toList();
    }

    private CacheManagerDto toManagerDto(
            CacheManagerSnapshot manager,
            Map<MetricKey, CacheMetricsAccumulator> metrics,
            List<String> warnings,
            Truncation truncation) {
        List<CacheSnapshot> sorted = sortedCaches(manager);
        if (sorted.size() > MAX_CACHES_PER_MANAGER) {
            warnings.add("Cache manager '" + manager.name() + "' was truncated to the first " + MAX_CACHES_PER_MANAGER
                    + " of " + sorted.size() + " caches.");
            sorted = sorted.subList(0, MAX_CACHES_PER_MANAGER);
            truncation.truncated = true;
        }
        List<CacheDto> caches = new ArrayList<>(sorted.size());
        for (CacheSnapshot cache : sorted) {
            caches.add(toCacheDto(manager.name(), cache, metrics, warnings, truncation));
        }
        return new CacheManagerDto(
                manager.name(),
                manager.type(),
                manager.noOp(),
                manager.composition(),
                manager.dynamicCaches(),
                manager.delegateTypes(),
                List.copyOf(caches));
    }

    private CacheDto toCacheDto(
            String managerName,
            CacheSnapshot cache,
            Map<MetricKey, CacheMetricsAccumulator> metrics,
            List<String> warnings,
            Truncation truncation) {
        CacheMetricsAccumulator metric = metrics.get(new MetricKey(managerName, cache.name()));
        if (metric == null) {
            metric = metrics.get(new MetricKey("*", cache.name()));
        }
        List<CacheTierDto> tiers = toTierDtos(managerName, cache, warnings, truncation);
        boolean opaque = tiers.isEmpty();
        return new CacheDto(
                managerName,
                cache.name(),
                cache.nativeType(),
                cache.size(),
                metric == null ? new CacheMetricsDto(false, null, null, null, null, null, null, null) : metric.toDto(),
                opaque,
                opaque
                        ? blankToDefault(
                                cache.opaqueReason(), "This cache implementation does not describe its storage.")
                        : null,
                tiers,
                CacheStatisticsAssembler.toDto(cache.statistics(), CacheStatisticsAssembler.SCOPE_CACHE));
    }

    private List<CacheTierDto> toTierDtos(
            String managerName, CacheSnapshot cache, List<String> warnings, Truncation truncation) {
        List<CacheTierSnapshot> tiers = cache.tiers().stream()
                .sorted(Comparator.comparingInt(CacheTierSnapshot::level)
                        .thenComparing(tier -> blankToDefault(tier.id(), "")))
                .toList();
        if (tiers.size() > MAX_TIERS_PER_CACHE) {
            warnings.add("Cache '" + managerName + "/" + cache.name() + "' was truncated to the first "
                    + MAX_TIERS_PER_CACHE + " of " + tiers.size() + " tiers.");
            tiers = tiers.subList(0, MAX_TIERS_PER_CACHE);
            truncation.truncated = true;
        }
        List<CacheTierDto> dtos = new ArrayList<>(tiers.size());
        for (CacheTierSnapshot tier : tiers) {
            dtos.add(new CacheTierDto(
                    tier.id(),
                    tier.name(),
                    tier.level(),
                    tier.implementationType(),
                    tier.locality(),
                    tier.maximumSize(),
                    tier.expiryPolicy(),
                    tier.policyNote(),
                    CacheStatisticsAssembler.toDto(tier.statistics(), CacheStatisticsAssembler.SCOPE_TIER)));
        }
        return List.copyOf(dtos);
    }

    private static String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private Map<MetricKey, CacheMetricsAccumulator> cacheMetrics() {
        MeterRegistry registry = this.registry.get();
        if (registry == null) {
            return Map.of();
        }
        Map<MetricKey, CacheMetricsAccumulator> metrics = new LinkedHashMap<>();
        for (Meter meter : registry.getMeters()) {
            if (!meterFilter.test(meter)) {
                continue;
            }
            String meterName = meter.getId().getName();
            String cacheName = meter.getId().getTag("cache");
            if (isBlank(cacheName)) {
                continue;
            }
            String managerName = firstNonBlank(
                    meter.getId().getTag("name"),
                    meter.getId().getTag("cacheManager"),
                    meter.getId().getTag("cache.manager"));
            MetricKey key = new MetricKey(isBlank(managerName) ? "*" : managerName, cacheName);
            CacheMetricsAccumulator accumulator =
                    metrics.computeIfAbsent(key, ignored -> new CacheMetricsAccumulator());
            addMetric(accumulator, meterName, meter);
        }
        return metrics;
    }

    private void addMetric(CacheMetricsAccumulator accumulator, String meterName, Meter meter) {
        OptionalDouble value = measurementValue(meter);
        if (value.isEmpty()) {
            return;
        }
        switch (meterName) {
            case "cache.gets" -> {
                String result = meter.getId().getTag("result");
                if ("hit".equalsIgnoreCase(result)) {
                    accumulator.addHits(value.getAsDouble());
                } else if ("miss".equalsIgnoreCase(result)) {
                    accumulator.addMisses(value.getAsDouble());
                }
            }
            case "cache.puts" -> accumulator.addPuts(value.getAsDouble());
            case "cache.evictions" -> accumulator.addEvictions(value.getAsDouble());
            case "cache.removals" -> accumulator.addRemovals(value.getAsDouble());
            case "cache.size" -> accumulator.setSize(value.getAsDouble());
            default -> {}
        }
    }

    private OptionalDouble measurementValue(Meter meter) {
        double sum = 0.0;
        boolean seen = false;
        for (Measurement measurement : meter.measure()) {
            double value = measurement.getValue();
            if (Double.isFinite(value)) {
                sum += value;
                seen = true;
            }
        }
        return seen ? OptionalDouble.of(sum) : OptionalDouble.empty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private record MetricKey(String managerName, String cacheName) {}

    /** Mutable flag threaded through report assembly so any dropped manager, cache or tier is visible. */
    private static final class Truncation {

        private boolean truncated;

        private Truncation(boolean truncated) {
            this.truncated = truncated;
        }
    }

    private static final class CacheMetricsAccumulator {

        private double hits;

        private boolean hitsSeen;

        private double misses;

        private boolean missesSeen;

        private double puts;

        private boolean putsSeen;

        private double evictions;

        private boolean evictionsSeen;

        private double removals;

        private boolean removalsSeen;

        private double size;

        private boolean sizeSeen;

        void addHits(double value) {
            this.hits += value;
            this.hitsSeen = true;
        }

        void addMisses(double value) {
            this.misses += value;
            this.missesSeen = true;
        }

        void addPuts(double value) {
            this.puts += value;
            this.putsSeen = true;
        }

        void addEvictions(double value) {
            this.evictions += value;
            this.evictionsSeen = true;
        }

        void addRemovals(double value) {
            this.removals += value;
            this.removalsSeen = true;
        }

        void setSize(double value) {
            this.size = value;
            this.sizeSeen = true;
        }

        CacheMetricsDto toDto() {
            boolean available = hitsSeen || missesSeen || putsSeen || evictionsSeen || removalsSeen || sizeSeen;
            Double hitRatio = null;
            if (hitsSeen || missesSeen) {
                double total = hits + misses;
                hitRatio = total == 0.0 ? 0.0 : hits / total;
            }
            return new CacheMetricsDto(
                    available,
                    hitsSeen ? hits : null,
                    missesSeen ? misses : null,
                    hitRatio,
                    putsSeen ? puts : null,
                    evictionsSeen ? evictions : null,
                    removalsSeen ? removals : null,
                    sizeSeen ? size : null);
        }
    }
}
