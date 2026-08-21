package io.github.jdubois.bootui.engine.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ActivityEntryDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder.CapturedEvent;
import org.junit.jupiter.api.Test;

class ResilienceActivityEntriesTests {

    private static CapturedEvent event(
            String outcome, String type, String target, Integer attempt, String failureCategory, String state) {
        return new CapturedEvent(
                7L,
                1_700_000_000_000L,
                "paymentGateway",
                type,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
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
        ActivityEntryDto entry = ResilienceActivityEntries.toEntry(
                event(
                        ResilienceVocabulary.OUTCOME_RETRY,
                        ResilienceVocabulary.TYPE_RETRY,
                        "PayClient#charge",
                        2,
                        "IOException",
                        null),
                "request-1");

        assertThat(entry.id()).isEqualTo("resilience-7");
        assertThat(entry.type()).isEqualTo("RESILIENCE");
        assertThat(entry.severity()).isEqualTo("WARN");
        assertThat(entry.summary()).isEqualTo("RETRY paymentGateway (retry)");
        assertThat(entry.detail()).isEqualTo("PayClient#charge · attempt 2 · IOException");
        assertThat(entry.durationMs()).isEqualTo(42L);
        assertThat(entry.correlationId()).isEqualTo("trace-9");
        assertThat(entry.parentId()).isEqualTo("request-1");
    }

    @Test
    void rendersAnExhaustedRetryAsAnError() {
        ActivityEntryDto entry = ResilienceActivityEntries.toEntry(
                event(
                        ResilienceVocabulary.OUTCOME_RETRY_EXHAUSTED,
                        ResilienceVocabulary.TYPE_RETRY,
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
        ActivityEntryDto entry = ResilienceActivityEntries.toEntry(
                event(
                        ResilienceVocabulary.OUTCOME_STATE_TRANSITION,
                        ResilienceVocabulary.TYPE_CIRCUIT_BREAKER,
                        null,
                        null,
                        null,
                        ResilienceVocabulary.STATE_OPEN),
                null);

        assertThat(entry.summary()).isEqualTo("STATE_TRANSITION paymentGateway (circuit breaker)");
        assertThat(entry.detail()).isEqualTo("state OPEN");
        assertThat(entry.severity()).isEqualTo("WARN");
    }

    @Test
    void rendersASuccessfulOutcomeWithNoDetailAsOk() {
        ActivityEntryDto entry = ResilienceActivityEntries.toEntry(
                event(ResilienceVocabulary.OUTCOME_SUCCESS, ResilienceVocabulary.TYPE_RETRY, null, null, null, null),
                null);

        assertThat(entry.severity()).isEqualTo("OK");
        assertThat(entry.detail()).isNull();
    }
}
