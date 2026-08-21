package io.github.jdubois.bootui.engine.faulttolerance;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyMetricsDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaultToleranceServiceTests {

    private static FaultTolerancePolicyDto policy(String name, String type, String provider, String target) {
        return new FaultTolerancePolicyDto(
                name,
                type,
                provider,
                FaultToleranceVocabulary.SOURCE_REGISTRY,
                target,
                null,
                List.of(new FaultTolerancePolicySettingDto(
                        "maxAttempts", "3", FaultToleranceVocabulary.PROVENANCE_CONFIGURED)),
                FaultTolerancePolicyMetricsDto.none());
    }

    private static FaultTolerancePolicyProvider provider(
            String id, boolean available, FaultTolerancePolicyDto... policies) {
        return new FaultTolerancePolicyProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public List<FaultTolerancePolicyDto> policies() {
                return Arrays.asList(policies);
            }
        };
    }

    @Test
    void reportsUnavailableWhenNoProviderIsPresent() {
        FaultToleranceReport report = new FaultToleranceService(List.of(), null, 50).report();

        assertThat(report.faultTolerancePresent()).isFalse();
        assertThat(report.unavailableReason()).contains("Resilience4j", "Spring Retry", "SmallRye Fault Tolerance");
        assertThat(report.policies()).isEmpty();
        assertThat(report.events()).isEmpty();
        assertThat(report.maxEvents()).isEqualTo(50);
    }

    @Test
    void reportsUnavailableWhenEveryProviderIsAbsent() {
        FaultToleranceReport report = new FaultToleranceService(
                        List.of(provider("resilience4j", false), provider("spring-retry", false)), null, 50)
                .report();

        assertThat(report.faultTolerancePresent()).isFalse();
        assertThat(report.providers()).isEmpty();
    }

    @Test
    void anAvailableProviderWithNoPoliciesStillMakesThePanelAvailable() {
        FaultToleranceReport report =
                new FaultToleranceService(List.of(provider("spring-retry", true)), null, 50).report();

        assertThat(report.faultTolerancePresent()).isTrue();
        assertThat(report.providers()).containsExactly("spring-retry");
        assertThat(report.totalPolicies()).isZero();
    }

    @Test
    void ordersPoliciesByTypeThenProviderThenNameThenTarget() {
        FaultToleranceReport report = new FaultToleranceService(
                        List.of(
                                provider(
                                        "spring-retry",
                                        true,
                                        policy("zeta", FaultToleranceVocabulary.TYPE_RETRY, "spring-retry", "B"),
                                        policy("alpha", FaultToleranceVocabulary.TYPE_RETRY, "spring-retry", "A")),
                                provider(
                                        "resilience4j",
                                        true,
                                        policy(
                                                "payments",
                                                FaultToleranceVocabulary.TYPE_TIME_LIMITER,
                                                "resilience4j",
                                                "C"),
                                        policy(
                                                "orders",
                                                FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER,
                                                "resilience4j",
                                                "D"))),
                        null,
                        50)
                .report();

        assertThat(report.policies())
                .extracting(FaultTolerancePolicyDto::name)
                .containsExactly("orders", "alpha", "zeta", "payments");
        assertThat(report.providers()).containsExactly("spring-retry", "resilience4j");
    }

    @Test
    void countsPoliciesByTypeAcrossEveryProvider() {
        FaultToleranceReport report = new FaultToleranceService(
                        List.of(provider(
                                "resilience4j",
                                true,
                                policy("a", FaultToleranceVocabulary.TYPE_RETRY, "resilience4j", null),
                                policy("b", FaultToleranceVocabulary.TYPE_RETRY, "resilience4j", null),
                                policy("c", FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER, "resilience4j", null))),
                        null,
                        50)
                .report();

        assertThat(report.policyCountsByType())
                .containsEntry(FaultToleranceVocabulary.TYPE_RETRY, 2)
                .containsEntry(FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER, 1);
    }

    @Test
    void degradesAFailingProviderIntoAWarningWithoutHidingTheOthers() {
        FaultTolerancePolicyProvider broken = new FaultTolerancePolicyProvider() {
            @Override
            public String providerId() {
                return "resilience4j";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<FaultTolerancePolicyDto> policies() {
                throw new IllegalStateException("registry not ready");
            }
        };

        FaultToleranceReport report = new FaultToleranceService(
                        List.of(broken, provider("spring-retry", true, policy("a", "RETRY", "spring-retry", null))),
                        null,
                        50)
                .report();

        assertThat(report.faultTolerancePresent()).isTrue();
        assertThat(report.warnings())
                .anySatisfy(
                        warning -> assertThat(warning).contains("resilience4j").contains("IllegalStateException"));
        assertThat(report.policies()).hasSize(1);
    }

    @Test
    void degradesALinkageErrorFromAnOptionalLibraryIntoAWarning() {
        FaultTolerancePolicyProvider broken = new FaultTolerancePolicyProvider() {
            @Override
            public String providerId() {
                return "resilience4j";
            }

            @Override
            public boolean available() {
                throw new NoClassDefFoundError("io/github/resilience4j/Missing");
            }

            @Override
            public List<FaultTolerancePolicyDto> policies() {
                return List.of();
            }
        };

        FaultToleranceReport report = new FaultToleranceService(List.of(broken), null, 50).report();

        assertThat(report.faultTolerancePresent()).isFalse();
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("NoClassDefFoundError"));
    }

    @Test
    void capsRenderedPoliciesButKeepsTheTotalAndCountsTruthful() {
        List<FaultTolerancePolicyDto> many = new ArrayList<>();
        for (int i = 0; i < FaultToleranceService.MAX_POLICIES + 25; i++) {
            many.add(policy("policy-" + i, FaultToleranceVocabulary.TYPE_RETRY, "resilience4j", null));
        }

        FaultToleranceReport report = new FaultToleranceService(
                        List.of(provider("resilience4j", true, many.toArray(new FaultTolerancePolicyDto[0]))), null, 50)
                .report();

        assertThat(report.policies()).hasSize(FaultToleranceService.MAX_POLICIES);
        assertThat(report.totalPolicies()).isEqualTo(FaultToleranceService.MAX_POLICIES + 25);
        assertThat(report.policyCountsByType())
                .containsEntry(FaultToleranceVocabulary.TYPE_RETRY, FaultToleranceService.MAX_POLICIES + 25);
        assertThat(report.warnings()).anySatisfy(warning -> assertThat(warning).contains("Showing the first"));
    }

    @Test
    void includesCapturedEventsBoundedByTheRecordersOwnBuffer() {
        FaultToleranceEventRecorder recorder = new FaultToleranceEventRecorder(true, 2);
        recorder.record("a", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("b", "RETRY", "resilience4j", null, "RETRY", 1, null, null);
        recorder.record("c", "RETRY", "resilience4j", null, "RETRY", 1, null, null);

        FaultToleranceReport report = new FaultToleranceService(
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
        FaultToleranceReport report = new FaultToleranceService(
                        List.of(provider("resilience4j", true, policy("a", "RETRY", "resilience4j", null))),
                        new FaultToleranceEventRecorder(false, 50),
                        50)
                .report();

        assertThat(report.faultTolerancePresent()).isTrue();
        assertThat(report.captureEnabled()).isFalse();
        assertThat(report.policies()).hasSize(1);
        assertThat(report.events()).isEmpty();
    }

    @Test
    void ignoresNullProvidersAndNullPolicies() {
        FaultTolerancePolicyProvider nullPolicies = new FaultTolerancePolicyProvider() {
            @Override
            public String providerId() {
                return "spring-retry";
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public List<FaultTolerancePolicyDto> policies() {
                return null;
            }
        };
        List<FaultTolerancePolicyProvider> providers = new ArrayList<>();
        providers.add(null);
        providers.add(nullPolicies);
        providers.add(provider("resilience4j", true, policy("a", "RETRY", "resilience4j", null), null));

        FaultToleranceReport report = new FaultToleranceService(providers, null, 50).report();

        assertThat(report.providers()).containsExactly("spring-retry", "resilience4j");
        assertThat(report.policies()).hasSize(1);
    }
}
