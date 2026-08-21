package io.github.jdubois.bootui.engine.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jdubois.bootui.core.dto.MetricDetailDto;
import io.github.jdubois.bootui.core.dto.MetricGroupDto;
import io.github.jdubois.bootui.core.dto.MetricMeasurementDto;
import io.github.jdubois.bootui.core.dto.MetricMeterDto;
import io.github.jdubois.bootui.core.dto.MetricProvenanceDto;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class MetricsReportProviderTests {

    private static MetricsReportProvider provider(MeterRegistry registry) {
        return provider(registry, meter -> true);
    }

    private static MetricsReportProvider provider(MeterRegistry registry, Predicate<Meter> filter) {
        Supplier<MeterRegistry> supplier = () -> registry;
        return new MetricsReportProvider(supplier, filter);
    }

    @Test
    void metricsGroupsMetersByNameAndAggregatesTagValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .description("Sample requests")
                .baseUnit("requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment();

        MetricsReport report = provider(registry).metrics();

        assertThat(report.metricsAvailable()).isTrue();
        assertThat(report.total()).isGreaterThan(0);
        MetricMeterDto meter = report.meters().stream()
                .filter(m -> m.name().equals("bootui.sample.requests"))
                .findFirst()
                .orElseThrow();
        assertThat(meter.description()).isEqualTo("Sample requests");
        assertThat(meter.baseUnit()).isEqualTo("requests");
        assertThat(meter.type()).isEqualTo("COUNTER");
        assertThat(meter.availableTags()).hasSize(1);
        assertThat(meter.availableTags().get(0).key()).isEqualTo("outcome");
        assertThat(meter.availableTags().get(0).values()).containsExactly("failure", "success");
        assertThat(report.availableTypes()).contains("COUNTER");
        assertThat(report.page().total()).isEqualTo(report.total());
        assertThat(report.page().returned()).isEqualTo(report.meters().size());
    }

    @Test
    void metricsFiltersAndPagesInDeterministicNameOrder() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("zeta.requests").description("Target traffic").register(registry);
        Counter.builder("alpha.requests").description("Target traffic").register(registry);
        Gauge.builder("middle.gauge", new AtomicInteger(), AtomicInteger::get)
                .description("Target traffic")
                .register(registry);

        MetricsReport report = provider(registry).metrics("target", "counter", "1", "1");

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.availableTypes()).containsExactly("COUNTER", "GAUGE");
        assertThat(report.meters()).extracting(MetricMeterDto::name).containsExactly("zeta.requests");
        assertThat(report.page()).isEqualTo(new io.github.jdubois.bootui.core.dto.PageMetadata(3, 2, 1, 1, 1, false));
    }

    @Test
    void metricsUsesBoundedDefaultsAndRejectsInvalidPaging() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (int index = 0; index < 205; index++) {
            registry.counter("sample." + String.format("%03d", index));
        }

        MetricsReport report = provider(registry).metrics();

        assertThat(report.meters()).hasSize(MetricsReportProvider.DEFAULT_METER_LIMIT);
        assertThat(report.page().hasMore()).isTrue();
        assertThatThrownBy(() -> provider(registry).metrics(null, null, "invalid", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric offset must be 0 or greater");
        assertThatThrownBy(() -> provider(registry).metrics(null, null, null, "1001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric limit must be between 1 and 1000");
        assertThatThrownBy(() -> provider(registry).metrics(null, "not-a-type", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Metric type must be one of:");
    }

    @Test
    void detailFiltersByTagsAndAggregatesFiniteMeasurements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "success")
                .register(registry)
                .increment(2);
        Counter.builder("bootui.sample.requests")
                .tag("outcome", "failure")
                .register(registry)
                .increment(3);

        MetricDetailDto detail = provider(registry).metric("bootui.sample.requests", List.of("outcome:success"));

        assertThat(detail.metricsAvailable()).isTrue();
        assertThat(detail.name()).isEqualTo("bootui.sample.requests");
        assertThat(detail.measurements()).contains(new MetricMeasurementDto("count", 2.0));
        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).tags().get(0).key()).isEqualTo("outcome");
        assertThat(detail.samples().get(0).tags().get(0).value()).isEqualTo("success");
    }

    @Test
    void detailPagesDeterministicallyAndAggregatesEveryMatchingSample() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("bootui.sample.requests")
                .tag("node", "charlie")
                .register(registry)
                .increment(3);
        Counter.builder("bootui.sample.requests")
                .tag("node", "alpha")
                .register(registry)
                .increment();
        Counter.builder("bootui.sample.requests")
                .tag("node", "bravo")
                .register(registry)
                .increment(2);

        MetricDetailDto detail = provider(registry).metric("bootui.sample.requests", null, "1", "1");

        assertThat(detail.totalSamples()).isEqualTo(3);
        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).tags().get(0).value()).isEqualTo("bravo");
        assertThat(detail.measurements()).contains(new MetricMeasurementDto("count", 6.0));
        assertThat(detail.samplePage())
                .isEqualTo(new io.github.jdubois.bootui.core.dto.PageMetadata(3, 3, 1, 1, 1, true));
        assertThat(detail.samplesTruncated()).isTrue();
    }

    @Test
    void detailBoundsTagValuesWhileRetainingDeterministicValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (int index = 104; index >= 0; index--) {
            Counter.builder("bootui.cardinality")
                    .tag("route", String.format("route-%03d", index))
                    .register(registry);
        }

        MetricDetailDto detail = provider(registry).metric("bootui.cardinality", null, null, "1");

        assertThat(detail.availableTags()).hasSize(1);
        assertThat(detail.availableTags().get(0).values())
                .hasSize(100)
                .startsWith("route-000")
                .endsWith("route-099");
        assertThat(detail.availableTags().get(0).truncated()).isTrue();
        assertThat(detail.totalSamples()).isEqualTo(105);
        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samplesTruncated()).isTrue();
    }

    @Test
    void detailUsesMaximumWhenAggregatingMaxStatistic() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Timer.builder("bootui.sample.latency")
                .tag("node", "one")
                .register(registry)
                .record(10, TimeUnit.MILLISECONDS);
        Timer.builder("bootui.sample.latency")
                .tag("node", "two")
                .register(registry)
                .record(25, TimeUnit.MILLISECONDS);

        MetricDetailDto detail = provider(registry).metric("bootui.sample.latency", null);

        assertThat(detail.measurements())
                .filteredOn(measurement -> measurement.statistic().equals("max"))
                .extracting(MetricMeasurementDto::value)
                .containsExactly(0.025);
    }

    @Test
    void detailAcceptsColonInTagValues() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicInteger value = new AtomicInteger(7);
        Gauge.builder("bootui.sample.gauge", value, AtomicInteger::get)
                .tags(Tags.of("uri", "http://localhost:8080/api"))
                .register(registry);

        MetricDetailDto detail =
                provider(registry).metric("bootui.sample.gauge", List.of("uri:http://localhost:8080/api"));

        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.measurements().get(0).value()).isEqualTo(7.0);
    }

    @Test
    void appliesMeterVisibilityPredicate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("http.server.requests")
                .tag("uri", "/bootui/api/beans")
                .register(registry)
                .increment(5);
        Counter.builder("http.server.requests")
                .tag("uri", "/api/orders")
                .register(registry)
                .increment(2);

        Predicate<Meter> hideBootUi = meter -> {
            String uri = meter.getId().getTag("uri");
            return uri == null || !uri.startsWith("/bootui");
        };

        MetricDetailDto detail = provider(registry, hideBootUi).metric("http.server.requests", null);

        assertThat(detail.samples()).hasSize(1);
        assertThat(detail.samples().get(0).tags().get(0).value()).isEqualTo("/api/orders");
        assertThat(detail.availableTags().get(0).values()).containsExactly("/api/orders");
    }

    @Test
    void detailRejectsMalformedTagFilters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("bootui.sample.requests").increment();

        assertThatThrownBy(() -> provider(registry).metric("bootui.sample.requests", List.of("malformed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key:value");
    }

    @Test
    void detailRejectsBlankNameAndInvalidPaging() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThatThrownBy(() -> provider(registry).metric(" ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric name must not be blank");
        assertThatThrownBy(() -> provider(registry).metric("sample", null, "-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric offset must be 0 or greater");
        assertThatThrownBy(() -> provider(registry).metric("sample", null, null, "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric limit must be between 1 and 1000");
    }

    @Test
    void metricsReportUnavailableWhenNoRegistry() {
        MetricsReport report = provider(null).metrics();

        assertThat(report.metricsAvailable()).isFalse();
        assertThat(report.total()).isZero();
        assertThat(report.meters()).isEmpty();
        assertThat(report.page().limit()).isEqualTo(MetricsReportProvider.DEFAULT_METER_LIMIT);
    }

    @Test
    void detailUnavailableWhenNoRegistry() {
        MetricDetailDto detail = provider(null).metric("anything", null);

        assertThat(detail.metricsAvailable()).isFalse();
        assertThat(detail.name()).isEqualTo("anything");
        assertThat(detail.samples()).isEmpty();
        assertThat(detail.samplePage().limit()).isEqualTo(MetricsReportProvider.DEFAULT_SAMPLE_LIMIT);
        assertThat(detail.samplesTruncated()).isFalse();
    }

    @Test
    void metricsGroupsMetersByProvenanceAndReconcilesCountsWithTheMatchedSet() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", new AtomicInteger(3), AtomicInteger::get)
                .baseUnit("bytes")
                .tag("area", "heap")
                .tag("id", "eden")
                .register(registry);
        Timer.builder("http.server.requests")
                .description("Duration of HTTP server request handling")
                .tag("uri", "/orders")
                .register(registry);
        Counter.builder("orders.processed").register(registry);

        MetricsReport report = provider(registry).metrics();

        assertThat(report.catalogueVersion()).isEqualTo(MeterFamilyCatalogue.VERSION);
        assertThat(report.groups()).extracting(MetricGroupDto::id).containsExactly("jvm", "http-server", "application");
        assertThat(report.groups().stream().mapToInt(MetricGroupDto::meterCount).sum())
                .isEqualTo(report.page().matched());

        MetricGroupDto jvm = group(report, "jvm");
        assertThat(jvm.label()).isEqualTo("JVM");
        assertThat(jvm.contributor()).isEqualTo("Micrometer JVM binders");
        assertThat(jvm.summary()).isNotBlank();
        assertThat(jvm.interpretation()).isNotBlank();
        assertThat(jvm.meterCount()).isEqualTo(1);
        assertThat(jvm.describedMeterCount())
                .as("the curated explanation does not count as registry documentation")
                .isZero();
        assertThat(jvm.families()).containsExactly("JVM memory");
        assertThat(jvm.commonTagKeys()).containsExactly("area", "id");
        assertThat(jvm.baseUnits()).containsExactly("bytes");

        MetricGroupDto httpServer = group(report, "http-server");
        assertThat(httpServer.meterCount()).isEqualTo(1);
        assertThat(httpServer.describedMeterCount())
                .as("meters carrying a registry description are counted as documented")
                .isEqualTo(1);
        assertThat(httpServer.families()).containsExactly("HTTP server requests");

        MetricGroupDto application = group(report, "application");
        assertThat(application.meterCount()).isEqualTo(1);
        assertThat(application.describedMeterCount()).isZero();
        assertThat(application.families()).isEmpty();
    }

    @Test
    void metricsExplainsMetersFromTheRegistryFirstAndTheCatalogueSecond() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", new AtomicInteger(3), AtomicInteger::get)
                .register(registry);
        Timer.builder("http.server.requests")
                .description("Duration of HTTP server request handling")
                .register(registry);
        Counter.builder("orders.processed").register(registry);

        MetricsReport report = provider(registry).metrics();

        MetricProvenanceDto curated = provenance(report, "jvm.memory.used");
        assertThat(curated.classified()).isTrue();
        assertThat(curated.groupId()).isEqualTo("jvm");
        assertThat(curated.familyId()).isEqualTo("jvm.memory");
        assertThat(curated.familyLabel()).isEqualTo("JVM memory");
        assertThat(curated.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_CURATED);
        assertThat(curated.explanation()).isEqualTo("Heap and non-heap memory usage per memory pool.");
        assertThat(curated.interpretation()).isNotBlank();

        MetricProvenanceDto nativeDescription = provenance(report, "http.server.requests");
        assertThat(nativeDescription.classified()).isTrue();
        assertThat(nativeDescription.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_NATIVE);
        assertThat(nativeDescription.explanation()).isEqualTo("Duration of HTTP server request handling");

        MetricProvenanceDto unknown = provenance(report, "orders.processed");
        assertThat(unknown.classified()).isFalse();
        assertThat(unknown.groupId()).isEqualTo(MeterFamilyCatalogue.APPLICATION_GROUP_ID);
        assertThat(unknown.familyId()).isNull();
        assertThat(unknown.explanation()).isNull();
        assertThat(unknown.explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_UNKNOWN);
    }

    @Test
    void metricsNeverLeaksTagValuesIntoProvenanceOrGroups() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("jvm.gc.pause").tag("cause", "s3cret-token-value").register(registry);

        MetricsReport report = provider(registry).metrics();

        assertThat(report.groups().toString()).doesNotContain("s3cret-token-value");
        assertThat(provenance(report, "jvm.gc.pause").toString()).doesNotContain("s3cret-token-value");
        assertThat(group(report, "jvm").commonTagKeys()).containsExactly("cause");
    }

    @Test
    void metricsFiltersByGroupWhileKeepingTheGroupListNavigable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", new AtomicInteger(3), AtomicInteger::get)
                .register(registry);
        Counter.builder("jvm.classes.unloaded").register(registry);
        Counter.builder("orders.processed").register(registry);

        MetricsReport report = provider(registry).metrics(null, null, "jvm", null, null, null, null);

        assertThat(report.meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("jvm.classes.unloaded", "jvm.memory.used");
        assertThat(report.page().matched()).isEqualTo(2);
        assertThat(report.page().total()).isEqualTo(3);
        assertThat(report.groups()).extracting(MetricGroupDto::id).containsExactly("jvm", "application");
        assertThat(group(report, "jvm").meterCount()).isEqualTo(report.page().matched());
    }

    @Test
    void metricsFiltersByProvenanceAndExplanationSource() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", new AtomicInteger(3), AtomicInteger::get)
                .register(registry);
        Timer.builder("http.server.requests").description("Native description").register(registry);
        Counter.builder("orders.processed").register(registry);

        MetricsReportProvider provider = provider(registry);

        assertThat(provider.metrics(null, null, null, "classified", null, null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("http.server.requests", "jvm.memory.used");
        assertThat(provider.metrics(null, null, null, "UNCLASSIFIED", null, null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("orders.processed");
        assertThat(provider.metrics(null, null, null, null, "native", null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("http.server.requests");
        assertThat(provider.metrics(null, null, null, null, "CURATED", null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("jvm.memory.used");
        assertThat(provider.metrics(null, null, null, null, "unknown", null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("orders.processed");
        assertThat(provider.metrics(null, null, "jvm", "classified", "CURATED", null, null)
                        .meters())
                .extracting(MetricMeterDto::name)
                .containsExactly("jvm.memory.used");
    }

    @Test
    void metricsRejectsUnknownProvenanceFiltersCanonically() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter.builder("orders.processed").register(registry);
        MetricsReportProvider provider = provider(registry);

        assertThatThrownBy(() -> provider.metrics(null, null, "not-a-group", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Metric group must be one of: application, cache");
        assertThatThrownBy(() -> provider.metrics(null, null, null, "maybe", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric provenance must be one of: classified, unclassified");
        assertThatThrownBy(() -> provider.metrics(null, null, null, null, "GUESSED", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Metric explanation source must be one of: CURATED, NATIVE, UNKNOWN");
    }

    @Test
    void metricsGroupsCoverEveryMatchingMeterEvenWhenThePageIsTruncated() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        for (int index = 0; index < 120; index++) {
            Counter.builder("jvm.gc.pause." + index).register(registry);
            Counter.builder("orders.processed." + index).register(registry);
        }

        MetricsReport report = provider(registry).metrics(null, null, null, null, null, null, "10");

        assertThat(report.meters()).hasSize(10);
        assertThat(report.page().matched()).isEqualTo(240);
        assertThat(report.groups().stream().mapToInt(MetricGroupDto::meterCount).sum())
                .isEqualTo(240);
        assertThat(group(report, "jvm").meterCount()).isEqualTo(120);
        assertThat(group(report, "application").meterCount()).isEqualTo(120);
    }

    @Test
    void groupSummariesStayBoundedForHighCardinalityRegistries() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        // Every meter carries the same 300 tag keys, so each key clears the "shared by most meters" threshold and
        // the caps are the only thing keeping the summary small.
        List<Tag> sharedTags = new ArrayList<>();
        for (int key = 0; key < 300; key++) {
            sharedTags.add(Tag.of(String.format("tag%03d", key), "value"));
        }
        for (int index = 0; index < 20; index++) {
            Counter.builder("orders.processed." + index)
                    .baseUnit("unit" + index)
                    .tags(Tags.of(sharedTags))
                    .register(registry);
        }

        MetricGroupDto application = group(provider(registry).metrics(), "application");

        assertThat(application.commonTagKeys())
                .as("shared tag keys are capped, and the cap actually bites")
                .hasSize(MetricsReportProvider.MAX_COMMON_TAG_KEYS);
        assertThat(application.baseUnits())
                .as("base units are capped")
                .hasSize(MetricsReportProvider.MAX_GROUP_BASE_UNITS);
        assertThat(application.families()).isEmpty();
        assertThat(application.meterCount()).isEqualTo(20);
    }

    @Test
    void detailCarriesProvenanceForTheRequestedMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("jvm.memory.used", new AtomicInteger(3), AtomicInteger::get)
                .register(registry);

        MetricDetailDto detail = provider(registry).metric("jvm.memory.used", null);

        assertThat(detail.provenance()).isNotNull();
        assertThat(detail.provenance().groupId()).isEqualTo("jvm");
        assertThat(detail.provenance().explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_CURATED);
    }

    @Test
    void unavailableRegistryReportsAnEmptyCatalogueRatherThanGuessing() {
        MetricsReport report = provider(null).metrics();

        assertThat(report.groups()).isEmpty();
        assertThat(report.catalogueVersion()).isEqualTo(MeterFamilyCatalogue.VERSION);
        assertThat(provider(null).metric("anything", null).provenance()).isNull();
    }

    @Test
    void unknownMeterNamesStillCarryProvenanceWhenARegistryIsPresent() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricDetailDto unknown = provider(registry).metric("orders.processed", null);

        assertThat(unknown.metricsAvailable()).isTrue();
        assertThat(unknown.provenance()).isNotNull();
        assertThat(unknown.provenance().classified()).isFalse();
        assertThat(unknown.provenance().groupId()).isEqualTo(MeterFamilyCatalogue.APPLICATION_GROUP_ID);
        assertThat(unknown.provenance().explanationSource()).isEqualTo(MeterProvenanceClassifier.SOURCE_UNKNOWN);

        MetricDetailDto curatedButAbsent = provider(registry).metric("jvm.gc.pause.absent", null);
        assertThat(curatedButAbsent.provenance().groupId()).isEqualTo("jvm");
        assertThat(curatedButAbsent.provenance().explanationSource())
                .isEqualTo(MeterProvenanceClassifier.SOURCE_CURATED);
    }

    private static MetricGroupDto group(MetricsReport report, String id) {
        return report.groups().stream()
                .filter(group -> group.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No group " + id + " in " + report.groups()));
    }

    private static MetricProvenanceDto provenance(MetricsReport report, String meterName) {
        return report.meters().stream()
                .filter(meter -> meter.name().equals(meterName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No meter " + meterName))
                .provenance();
    }
}
