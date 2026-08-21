package io.github.jdubois.bootui.autoconfigure.resilience;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import io.github.jdubois.bootui.autoconfigure.web.ResilienceController;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicySettingDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceService;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.jdubois.bootui.spi.ResiliencePolicyProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the read-only {@code /bootui/api/resilience} contract: the report's shape is stable, the panel
 * fails closed with an explicit reason when no library is present, and no mutating verb is exposed at all.
 */
class ResilienceControllerTests {

    private static MockMvc mvc(ResilienceService service) {
        return standaloneSetup(new ResilienceController(service)).build();
    }

    private static ResiliencePolicyProvider provider(ResiliencePolicyDto... policies) {
        return new ResiliencePolicyProvider() {
            @Override
            public String providerId() {
                return ResilienceVocabulary.PROVIDER_RESILIENCE4J;
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<ResiliencePolicyDto> policies() {
                return List.of(policies);
            }
        };
    }

    @Test
    void failsClosedWithAnExplicitReasonWhenNoResilienceLibraryIsPresent() throws Exception {
        mvc(new ResilienceService(List.of(), null, 200))
                .perform(get("/bootui/api/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resiliencePresent").value(false))
                .andExpect(jsonPath("$.unavailableReason").isNotEmpty())
                .andExpect(jsonPath("$.totalPolicies").value(0))
                .andExpect(jsonPath("$.policies").isEmpty())
                .andExpect(jsonPath("$.events").isEmpty())
                .andExpect(jsonPath("$.maxEvents").value(200));
    }

    @Test
    void servesTheStablePolicyAndEventShape() throws Exception {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 50);
        recorder.record(
                "payments",
                ResilienceVocabulary.TYPE_RETRY,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                "PayClient#charge",
                ResilienceVocabulary.OUTCOME_RETRY,
                2,
                17L,
                "IOException");
        ResiliencePolicyDto policy = new ResiliencePolicyDto(
                "payments",
                ResilienceVocabulary.TYPE_CIRCUIT_BREAKER,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                ResilienceVocabulary.SOURCE_REGISTRY,
                "PayClient#charge",
                ResilienceVocabulary.STATE_CLOSED,
                List.of(new ResiliencePolicySettingDto(
                        "failureRateThreshold", "50%", ResilienceVocabulary.PROVENANCE_DEFAULT)),
                new ResiliencePolicyMetricsDto(4L, 1L, null, null, null, null, 20.0, 5L));

        mvc(new ResilienceService(List.of(provider(policy)), recorder, 200))
                .perform(get("/bootui/api/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resiliencePresent").value(true))
                .andExpect(jsonPath("$.captureEnabled").value(true))
                .andExpect(jsonPath("$.providers[0]").value(ResilienceVocabulary.PROVIDER_RESILIENCE4J))
                .andExpect(jsonPath("$.totalPolicies").value(1))
                .andExpect(jsonPath("$.policyCountsByType.CIRCUIT_BREAKER").value(1))
                .andExpect(jsonPath("$.policies[0].name").value("payments"))
                .andExpect(jsonPath("$.policies[0].state").value("CLOSED"))
                .andExpect(jsonPath("$.policies[0].target").value("PayClient#charge"))
                .andExpect(jsonPath("$.policies[0].settings[0].name").value("failureRateThreshold"))
                .andExpect(jsonPath("$.policies[0].settings[0].provenance").value("DEFAULT"))
                .andExpect(jsonPath("$.policies[0].metrics.successfulCalls").value(4))
                .andExpect(jsonPath("$.policies[0].metrics.failureRatePercent").value(20.0))
                .andExpect(jsonPath("$.events[0].policyName").value("payments"))
                .andExpect(jsonPath("$.events[0].outcome").value("RETRY"))
                .andExpect(jsonPath("$.events[0].attempt").value(2))
                .andExpect(jsonPath("$.events[0].durationMillis").value(17))
                .andExpect(jsonPath("$.events[0].failureCategory").value("IOException"));
    }

    @Test
    void reportsCaptureDisabledWhileStillServingPolicies() throws Exception {
        ResiliencePolicyDto policy = new ResiliencePolicyDto(
                "payments",
                ResilienceVocabulary.TYPE_RETRY,
                ResilienceVocabulary.PROVIDER_SPRING_RETRY,
                ResilienceVocabulary.SOURCE_ANNOTATION,
                null,
                null,
                List.of(),
                ResiliencePolicyMetricsDto.none());

        mvc(new ResilienceService(List.of(provider(policy)), new ResilienceEventRecorder(false, 50), 200))
                .perform(get("/bootui/api/resilience"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.captureEnabled").value(false))
                .andExpect(jsonPath("$.totalPolicies").value(1))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void exposesNoMutatingVerbBecauseThePanelIsStrictlyCaptureOnly() throws Exception {
        MockMvc mvc = mvc(new ResilienceService(List.of(), null, 200));

        mvc.perform(post("/bootui/api/resilience")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/bootui/api/resilience")).andExpect(status().isMethodNotAllowed());
    }
}
