package io.github.jdubois.bootui.engine.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ResilienceEventDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder.CapturedEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResilienceEventRecorderTests {

    @Test
    void capturesEventsNewestFirst() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);

        recorder.record("first", "RETRY", "resilience4j", "Target#a", "RETRY", 1, 12L, "IOException");
        recorder.record("second", "RETRY", "resilience4j", "Target#b", "RETRY_EXHAUSTED", 3, 40L, "IOException");

        List<CapturedEvent> events = recorder.recent();
        assertThat(events).extracting(CapturedEvent::policyName).containsExactly("second", "first");
        assertThat(recorder.totalCaptured()).isEqualTo(2);
    }

    @Test
    void evictsOldestBeyondTheConfiguredBound() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 2);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("c", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        assertThat(recorder.recent()).extracting(CapturedEvent::policyName).containsExactly("c", "b");
        assertThat(recorder.totalCaptured()).isEqualTo(3);
    }

    @Test
    void hardCapsTheBufferRegardlessOfConfiguredSize() {
        assertThat(new ResilienceEventRecorder(true, Integer.MAX_VALUE).getMaxEntries())
                .isEqualTo(ResilienceEventRecorder.MAX_BUFFER_SIZE);
        assertThat(new ResilienceEventRecorder(true, 0).getMaxEntries()).isEqualTo(1);
        assertThat(new ResilienceEventRecorder(true, -5).getMaxEntries()).isEqualTo(1);
    }

    @Test
    void capturesNothingWhenDisabled() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(false, 10);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.recordStateTransition("cb", "resilience4j", null, "OPEN");

        assertThat(recorder.isEnabled()).isFalse();
        assertThat(recorder.recent()).isEmpty();
        assertThat(recorder.totalCaptured()).isZero();
    }

    @Test
    void rejectsBlankPolicyNamesAndOutcomesRatherThanRecordingEmptyRows() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);

        recorder.record(null, "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("  ", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("a", "RETRY", "resilience4j", null, null, 1, null, null);

        assertThat(recorder.recent()).isEmpty();
    }

    @Test
    void truncatesFreeTextMetadataAndNormalizesNumbers() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);
        String longName = "x".repeat(ResilienceEventRecorder.MAX_METADATA_LENGTH + 50);

        recorder.record(longName, "RETRY", "resilience4j", longName, "RETRY", -3, -20L, longName);

        CapturedEvent event = recorder.recent().get(0);
        assertThat(event.policyName()).hasSize(ResilienceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.target()).hasSize(ResilienceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.failureCategory()).hasSize(ResilienceEventRecorder.MAX_METADATA_LENGTH);
        assertThat(event.attempt()).isEqualTo(1);
        assertThat(event.durationMillis()).isZero();
    }

    @Test
    void recordsStateTransitionsInTheirOwnFieldRatherThanTheFailureCategory() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);

        recorder.recordStateTransition("payments", "resilience4j", "PaymentClient", "OPEN");

        CapturedEvent event = recorder.recent().get(0);
        assertThat(event.outcome()).isEqualTo(ResilienceVocabulary.OUTCOME_STATE_TRANSITION);
        assertThat(event.policyType()).isEqualTo(ResilienceVocabulary.TYPE_CIRCUIT_BREAKER);
        assertThat(event.state()).isEqualTo("OPEN");
        assertThat(event.failureCategory()).isNull();
        assertThat(event.attempt()).isNull();
    }

    @Test
    void flattensToTheStableContractWithTheRequestedCap() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);
        recorder.setTraceIdProvider(() -> "trace-1");
        recorder.record("a", "RETRY", "resilience4j", "T", "RETRY", 2, 7L, "IOException");
        recorder.record("b", "RETRY", "resilience4j", "T", "RETRY", 2, 7L, "IOException");

        List<ResilienceEventDto> dtos = recorder.recentDtos(1);

        assertThat(dtos).hasSize(1);
        ResilienceEventDto dto = dtos.get(0);
        assertThat(dto.policyName()).isEqualTo("b");
        assertThat(dto.traceId()).isEqualTo("trace-1");
        assertThat(dto.id()).isEqualTo("resilience-2");
        assertThat(recorder.recentDtos(0)).hasSize(2);
    }

    @Test
    void notifiesSubscribersUntilTheyUnsubscribe() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);
        AtomicInteger notifications = new AtomicInteger();
        Runnable unsubscribe = recorder.subscribe(notifications::incrementAndGet);

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(notifications.get()).isEqualTo(1);

        unsubscribe.run();
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(notifications.get()).isEqualTo(1);
    }

    @Test
    void clearEmptiesTheBufferWithoutBreakingSubsequentCapture() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);
        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        recorder.clear();
        assertThat(recorder.recent()).isEmpty();

        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        assertThat(recorder.recent()).hasSize(1);
    }

    @Test
    void survivesATraceIdProviderThatThrows() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 10);
        recorder.setTraceIdProvider(() -> {
            throw new IllegalStateException("no context");
        });

        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        assertThat(recorder.recent()).hasSize(1);
        assertThat(recorder.recent().get(0).traceId()).isNull();
    }
}
