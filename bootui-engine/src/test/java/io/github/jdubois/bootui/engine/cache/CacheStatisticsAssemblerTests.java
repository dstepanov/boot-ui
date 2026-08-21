package io.github.jdubois.bootui.engine.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.CacheStatisticsDto;
import io.github.jdubois.bootui.spi.CacheStatisticsSnapshot;
import org.junit.jupiter.api.Test;

/**
 * The statistics semantics the whole Cache panel rests on: an absent counter is never rendered as zero, an
 * impossible counter is dropped, and a hit ratio is only derived from counters the adapter declared
 * comparable and only when something was actually recorded.
 */
class CacheStatisticsAssemblerTests {

    @Test
    void reportsNoStatisticsSourceWhenTheAdapterSuppliedNone() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(null, CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.available()).isFalse();
        assertThat(dto.source()).isEqualTo(CacheStatisticsAssembler.SOURCE_NONE);
        assertThat(dto.scope()).isEqualTo(CacheStatisticsAssembler.SCOPE_CACHE);
        assertThat(dto.unavailableReason()).isNotBlank();
        assertThat(dto.hits()).isNull();
        assertThat(dto.hitRatio()).isNull();
    }

    @Test
    void keepsTheAdapterReasonWhenTheProviderIsNotRecording() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.unavailable("Caffeine", "This cache was not built with recordStats()."),
                CacheStatisticsAssembler.SCOPE_TIER);

        assertThat(dto.available()).isFalse();
        assertThat(dto.provider()).isEqualTo("Caffeine");
        assertThat(dto.scope()).isEqualTo(CacheStatisticsAssembler.SCOPE_TIER);
        assertThat(dto.unavailableReason()).isEqualTo("This cache was not built with recordStats().");
        // A provider that is not recording must not render as a genuine all-zero series.
        assertThat(dto.hits()).isNull();
        assertThat(dto.misses()).isNull();
    }

    @Test
    void substitutesAReasonWhenTheAdapterGaveNone() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.unavailable("Caffeine", "  "), CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.unavailableReason()).isNotBlank();
    }

    @Test
    void derivesBothRatiosFromComparableCounters() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.recording("Caffeine", CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                        .hits(75)
                        .misses(25)
                        .requests(100)
                        .countersComparable()
                        .build(),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.available()).isTrue();
        assertThat(dto.source()).isEqualTo(CacheStatisticsAssembler.SOURCE_NATIVE);
        assertThat(dto.window()).isEqualTo(CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME);
        assertThat(dto.hitRatio()).isEqualTo(0.75d);
        assertThat(dto.missRatio()).isEqualTo(0.25d);
        assertThat(dto.ratioUnavailableReason()).isNull();
    }

    @Test
    void refusesARatioForAnIdleCacheRatherThanReportingZeroPercent() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.recording("Caffeine", CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                        .hits(0)
                        .misses(0)
                        .countersComparable()
                        .build(),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.available()).isTrue();
        assertThat(dto.hits()).isEqualTo(0d);
        assertThat(dto.hitRatio()).isNull();
        assertThat(dto.missRatio()).isNull();
        assertThat(dto.ratioUnavailableReason()).isNotBlank();
    }

    @Test
    void refusesARatioWhenTheAdapterDidNotDeclareTheCountersComparable() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.recording("Mixed", CacheStatisticsSnapshot.WINDOW_UNKNOWN)
                        .hits(9)
                        .misses(1)
                        .build(),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.hits()).isEqualTo(9d);
        assertThat(dto.misses()).isEqualTo(1d);
        assertThat(dto.hitRatio()).isNull();
        assertThat(dto.ratioUnavailableReason()).isNotBlank();
    }

    @Test
    void refusesARatioWhenOnlyOneSideOfItIsExposed() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.recording("Partial", CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                        .hits(9)
                        .countersComparable()
                        .build(),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.hits()).isEqualTo(9d);
        assertThat(dto.misses()).isNull();
        assertThat(dto.hitRatio()).isNull();
        assertThat(dto.ratioUnavailableReason()).isNotBlank();
    }

    @Test
    void dropsImpossibleCountersInsteadOfRenderingThem() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                new CacheStatisticsSnapshot(
                        true,
                        "Broken",
                        CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME,
                        null,
                        null,
                        Double.NaN,
                        -5d,
                        Double.POSITIVE_INFINITY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        12d,
                        true),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.requests()).isNull();
        assertThat(dto.hits()).isNull();
        assertThat(dto.misses()).isNull();
        assertThat(dto.size()).isEqualTo(12d);
        assertThat(dto.hitRatio()).isNull();
        assertThat(dto.available()).isTrue();
    }

    @Test
    void reportsUnavailableWhenEveryCounterWasImpossible() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                new CacheStatisticsSnapshot(
                        true,
                        "Broken",
                        CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME,
                        null,
                        null,
                        Double.NaN,
                        Double.NaN,
                        Double.NEGATIVE_INFINITY,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        true),
                CacheStatisticsAssembler.SCOPE_CACHE);

        assertThat(dto.available()).isFalse();
        assertThat(dto.source()).isEqualTo(CacheStatisticsAssembler.SOURCE_NONE);
        assertThat(dto.unavailableReason()).isNotBlank();
    }

    @Test
    void carriesTheProviderWindowAndStartInstantThroughUnchanged() {
        CacheStatisticsDto dto = CacheStatisticsAssembler.toDto(
                CacheStatisticsSnapshot.recording(
                                "Spring Data Redis", CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                        .since("2024-05-01T10:15:30Z")
                        .hits(4)
                        .misses(1)
                        .puts(3)
                        .removals(2)
                        .countersComparable()
                        .build(),
                CacheStatisticsAssembler.SCOPE_TIER);

        assertThat(dto.provider()).isEqualTo("Spring Data Redis");
        assertThat(dto.since()).isEqualTo("2024-05-01T10:15:30Z");
        assertThat(dto.window()).isEqualTo(CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME);
        assertThat(dto.puts()).isEqualTo(3d);
        assertThat(dto.removals()).isEqualTo(2d);
        assertThat(dto.evictions()).isNull();
        assertThat(dto.hitRatio()).isEqualTo(0.8d);
    }

    @Test
    void leavesCountersUnsetByTheBuilderNull() {
        CacheStatisticsSnapshot snapshot = CacheStatisticsSnapshot.recording(
                        "Caffeine", CacheStatisticsSnapshot.WINDOW_APPLICATION_LIFETIME)
                .hits(1)
                .build();

        assertThat(snapshot.puts()).isNull();
        assertThat(snapshot.removals()).isNull();
        assertThat(snapshot.countersComparable()).isFalse();
        assertThat(snapshot.available()).isTrue();
    }
}
