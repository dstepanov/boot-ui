package io.github.jdubois.bootui.engine.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder.CapturedEvent;
import org.junit.jupiter.api.Test;

class FaultToleranceActivityEntriesTests {

    private static CapturedEvent event(
            String outcome, String type, String target, Integer attempt, String failureCategory, String state) {
        return new CapturedEvent(
                7L,
                1_700_000_000_000L,
                "paymentGateway",
                type,
                FaultToleranceVocabulary.PROVIDER_RESILIENCE4J,
                target,
                outcome,
                attempt,
                42L,
                failureCategory,
                state,
                "trace-9");
    }

    @Test
    void rendersARetryAsAWarningWithAttemptAndFailureCategory() {
        ActivityEntryDto entry = FaultToleranceActivityEntries.toEntry(
                event(
                        FaultToleranceVocabulary.OUTCOME_RETRY,
                        FaultToleranceVocabulary.TYPE_RETRY,
                        "PayClient#charge",
                        2,
                        "IOException",
                        null),
                "request-1");

        assertThat(entry.id()).isEqualTo("fault-tolerance-7");
        assertThat(entry.type()).isEqualTo("FAULT_TOLERANCE");
        assertThat(entry.severity()).isEqualTo("WARN");
        assertThat(entry.summary()).isEqualTo("RETRY paymentGateway (retry)");
        assertThat(entry.detail()).isEqualTo("PayClient#charge · attempt 2 · IOException");
        assertThat(entry.durationMs()).isEqualTo(42L);
        assertThat(entry.correlationId()).isEqualTo("trace-9");
        assertThat(entry.parentId()).isEqualTo("request-1");
    }

    @Test
    void rendersAnExhaustedRetryAsAnError() {
        ActivityEntryDto entry = FaultToleranceActivityEntries.toEntry(
                event(
                        FaultToleranceVocabulary.OUTCOME_RETRY_EXHAUSTED,
                        FaultToleranceVocabulary.TYPE_RETRY,
                        null,
                        3,
                        "TimeoutException",
                        null),
                null);

        assertThat(entry.severity()).isEqualTo("ERROR");
        assertThat(entry.detail()).isEqualTo("attempt 3 · TimeoutException");
        assertThat(entry.parentId()).isNull();
    }

    @Test
    void rendersAStateTransitionWithTheDestinationStateRatherThanAFailureCategory() {
        ActivityEntryDto entry = FaultToleranceActivityEntries.toEntry(
                event(
                        FaultToleranceVocabulary.OUTCOME_STATE_TRANSITION,
                        FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                        null,
                        null,
                        null,
                        FaultToleranceVocabulary.STATE_OPEN),
                null);

        assertThat(entry.summary()).isEqualTo("STATE_TRANSITION paymentGateway (circuit breaker)");
        assertThat(entry.detail()).isEqualTo("state OPEN");
        assertThat(entry.severity()).isEqualTo("WARN");
    }

    @Test
    void rendersASuccessfulOutcomeWithNoDetailAsOk() {
        ActivityEntryDto entry = FaultToleranceActivityEntries.toEntry(
                event(
                        FaultToleranceVocabulary.OUTCOME_SUCCESS,
                        FaultToleranceVocabulary.TYPE_RETRY,
                        null,
                        null,
                        null,
                        null),
                null);

        assertThat(entry.severity()).isEqualTo("OK");
        assertThat(entry.detail()).isNull();
    }
}
