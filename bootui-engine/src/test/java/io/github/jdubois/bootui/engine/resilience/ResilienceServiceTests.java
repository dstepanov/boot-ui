package io.github.jdubois.bootui.engine.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicySettingDto;
import io.github.jdubois.bootui.core.dto.ResilienceReport;
import io.github.jdubois.bootui.spi.ResiliencePolicyProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResilienceServiceTests {

    private static ResiliencePolicyDto policy(String name, String type, String provider, String target) {
        return new ResiliencePolicyDto(
                name,
                type,
                provider,
                ResilienceVocabulary.SOURCE_REGISTRY,
                target,
                null,
                List.of(new ResiliencePolicySettingDto("maxAttempts", "3", ResilienceVocabulary.PROVENANCE_CONFIGURED)),
                ResiliencePolicyMetricsDto.none());
    }

    private static ResiliencePolicyProvider provider(String id, boolean available, ResiliencePolicyDto... policies) {
        return new ResiliencePolicyProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public List<ResiliencePolicyDto> policies() {
                return Arrays.asList(policies);
            }
        };
    }

    @Test
    void reportsUnavailableWhenNoProviderIsPresent() {
        ResilienceReport report = new ResilienceService(List.of(), null, 50).report();

        assertThat(report.resiliencePresent()).isFalse();
        assertThat(report.unavailableReason()).contains("Resilience4j", "Spring Retry", "SmallRye Fault Tolerance");
        assertThat(report.policies()).isEmpty();
        assertThat(report.events()).isEmpty();
        assertThat(report.maxEvents()).isEqualTo(50);
    }

    @Test
    void reportsUnavailableWhenEveryProviderIsAbsent() {
        ResilienceReport report = new ResilienceService(
                        List.of(provider("resilience4j", false), provider("spring-retry", false)), null, 50)
                .report();

        assertThat(report.resiliencePresent()).isFalse();
        assertThat(report.providers()).isEmpty();
    }

    @Test
    void anAvailableProviderWithNoPoliciesStillMakesThePanelAvailable() {
        ResilienceReport report = new ResilienceService(List.of(provider("spring-retry", true)), null, 50).report();

        assertThat(report.resiliencePresent()).isTrue();
        assertThat(report.providers()).containsExactly("spring-retry");
        assertThat(report.totalPolicies()).isZero();
    }

    @Test
    void ordersPoliciesByTypeThenProviderThenNameThenTarget() {
        ResilienceReport report = new ResilienceService(
                        List.of(
                                provider(
                                        "spring-retry",
                                        true,
                                        policy("zeta", ResilienceVocabulary.TYPE_RETRY, "spring-retry", "B"),
                                        policy("alpha", ResilienceVocabulary.TYPE_RETRY, "spring-retry", "A")),
                                provider(
                                        "resilience4j",
                                        true,
                                        policy("payments", ResilienceVocabulary.TYPE_TIME_LIMITER, "resilience4j", "C"),
                                        policy(
                                                "orders",
                                                ResilienceVocabulary.TYPE_CIRCUIT_BREAKER,
                                                "resilience4j",
                                                "D"))),
                        null,
                        50)
                .report();

        assertThat(report.policies())
                .extracting(ResiliencePolicyDto::name)
                .containsExactly("orders", "alpha", "zeta", "payments");
        assertThat(report.providers()).containsExactly("spring-retry", "resilience4j");
    }

    @Test
    void countsPoliciesByTypeAcrossEveryProvider() {
        ResilienceReport report = new ResilienceService(
                        List.of(provider(
                                "resilience4j",
                                true,
                                policy("a", ResilienceVocabulary.TYPE_RETRY, "resilience4j", null),
                                policy("b", ResilienceVocabulary.TYPE_RETRY, "resilience4j", null),
                                policy("c", ResilienceVocabulary.TYPE_CIRCUIT_BREAKER, "resilience4j", null))),
                        null,
                        50)
                .report();

        assertThat(report.policyCountsByType())
                .containsEntry(ResilienceVocabulary.TYPE_RETRY, 2)
                .containsEntry(ResilienceVocabulary.TYPE_CIRCUIT_BREAKER, 1);
    }

    @Test
    void degradesAFailingProviderIntoAWarningWithoutHidingTheOthers() {
        ResiliencePolicyProvider broken = new ResiliencePolicyProvider() {
            @Override
            public String providerId() {
                return "resilience4j";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<ResiliencePolicyDto> policies() {
                throw new IllegalStateException("registry not ready");
            }
        };

        ResilienceReport report = new ResilienceService(
                        List.of(broken, provider("spring-retry", true, policy("a", "RETRY", "spring-retry", null))),
                        null,
                        50)
                .report();

        assertThat(report.resiliencePresent()).isTrue();
        assertThat(report.warnings())
                .anySatisfy(
                        warning -> assertThat(warning).contains("resilience4j").contains("IllegalStateException"));
        assertThat(report.policies()).hasSize(1);
    }

    @Test
    void degradesALinkageErrorFromAnOptionalLibraryIntoAWarning() {
        ResiliencePolicyProvider broken = new ResiliencePolicyProvider() {
            @Override
            public String providerId() {
                return "resilience4j";
            }

            @Override
            public boolean available() {
                throw new NoClassDefFoundError("io/github/resilience4j/Missing");
            }

            @Override
            public List<ResiliencePolicyDto> policies() {
                return List.of();
            }
        };

        ResilienceReport report = new ResilienceService(List.of(broken), null, 50).report();

        assertThat(report.resiliencePresent()).isFalse();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("NoClassDefFoundError"));
    }

    @Test
    void capsRenderedPoliciesButKeepsTheTotalAndCountsTruthful() {
        List<ResiliencePolicyDto> many = new ArrayList<>();
        for (int i = 0; i < ResilienceService.MAX_POLICIES + 25; i++) {
            many.add(policy("policy-" + i, ResilienceVocabulary.TYPE_RETRY, "resilience4j", null));
        }

        ResilienceReport report = new ResilienceService(
                        List.of(provider("resilience4j", true, many.toArray(new ResiliencePolicyDto[0]))), null, 50)
                .report();

        assertThat(report.policies()).hasSize(ResilienceService.MAX_POLICIES);
        assertThat(report.totalPolicies()).isEqualTo(ResilienceService.MAX_POLICIES + 25);
        assertThat(report.policyCountsByType())
                .containsEntry(ResilienceVocabulary.TYPE_RETRY, ResilienceService.MAX_POLICIES + 25);
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("Showing the first"));
    }

    @Test
    void includesCapturedEventsBoundedByTheRecordersOwnBuffer() {
        ResilienceEventRecorder recorder = new ResilienceEventRecorder(true, 2);
        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("c", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        ResilienceReport report = new ResilienceService(
                        List.of(provider("resilience4j", true, policy("a", "RETRY", "resilience4j", null))),
                        recorder,
                        500)
                .report();

        assertThat(report.captureEnabled()).isTrue();
        assertThat(report.maxEvents()).isEqualTo(2);
        assertThat(report.events()).hasSize(2);
    }

    @Test
    void reportsCaptureDisabledWhileStillListingPolicies() {
        ResilienceReport report = new ResilienceService(
                        List.of(provider("resilience4j", true, policy("a", "RETRY", "resilience4j", null))),
                        new ResilienceEventRecorder(false, 50),
                        50)
                .report();

        assertThat(report.resiliencePresent()).isTrue();
        assertThat(report.captureEnabled()).isFalse();
        assertThat(report.policies()).hasSize(1);
        assertThat(report.events()).isEmpty();
    }

    @Test
    void ignoresNullProvidersAndNullPolicies() {
        ResiliencePolicyProvider nullPolicies = new ResiliencePolicyProvider() {
            @Override
            public String providerId() {
                return "spring-retry";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<ResiliencePolicyDto> policies() {
                return null;
            }
        };
        List<ResiliencePolicyProvider> providers = new ArrayList<>();
        providers.add(null);
        providers.add(nullPolicies);
        providers.add(provider("resilience4j", true, policy("a", "RETRY", "resilience4j", null), null));

        ResilienceReport report = new ResilienceService(providers, null, 50).report();

        assertThat(report.providers()).containsExactly("spring-retry", "resilience4j");
        assertThat(report.policies()).hasSize(1);
    }
}
