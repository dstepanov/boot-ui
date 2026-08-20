package io.github.jdubois.bootui.autoconfigure.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.BootUiAutoConfiguration;
import io.github.jdubois.bootui.core.dto.ResilienceReport;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceService;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies the resilience backend the auto-configuration assembles, rather than the individual providers:
 * capture must be attached to the application's own Resilience4j publishers by the time the application is
 * running, and it must not be attached at all when the panel or the feature is switched off.
 */
class ResilienceAutoConfigurationTests {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BootUiAutoConfiguration.class))
            .withUserConfiguration(SampleRegistryConfiguration.class)
            .withPropertyValues("bootui.enabled=ON");

    @Test
    void capturesEventsProducedBeforeThePanelIsEverOpened() {
        runner.run(context -> {
            // No BootUI request has happened yet: the panel must already be listening.
            context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("orders")
                    .transitionToOpenState();

            ResilienceReport report = context.getBean(ResilienceService.class).report();

            assertThat(report.resiliencePresent()).isTrue();
            assertThat(report.captureEnabled()).isTrue();
            assertThat(report.events()).hasSize(1);
            assertThat(report.events().get(0).outcome()).isEqualTo(ResilienceVocabulary.OUTCOME_STATE_TRANSITION);
            assertThat(report.events().get(0).policyName()).isEqualTo("orders");
            assertThat(report.events().get(0).state()).isEqualTo(ResilienceVocabulary.STATE_OPEN);
        });
    }

    @Test
    void listsTheApplicationRegistryPoliciesWithLiveState() {
        runner.run(context -> {
            ResilienceReport report = context.getBean(ResilienceService.class).report();

            assertThat(report.providers()).contains(ResilienceVocabulary.PROVIDER_RESILIENCE4J);
            assertThat(report.policies())
                    .anySatisfy(policy -> assertThat(policy.name()).isEqualTo("orders"));
            assertThat(report.policyCountsByType()).containsEntry(ResilienceVocabulary.TYPE_CIRCUIT_BREAKER, 1);
        });
    }

    @Test
    void attachesNoConsumerWhenCaptureIsDisabled() {
        runner.withPropertyValues("bootui.resilience.enabled=false").run(context -> {
            assertThat(context.getBean(ResilienceEventRecorder.class).isEnabled())
                    .isFalse();

            context.getBean(CircuitBreakerRegistry.class)
                    .circuitBreaker("orders")
                    .transitionToOpenState();

            ResilienceReport report = context.getBean(ResilienceService.class).report();
            assertThat(report.captureEnabled()).isFalse();
            assertThat(report.events()).isEmpty();
            // The inventory is still read live; only capture is off.
            assertThat(report.policies()).isNotEmpty();
        });
    }

    @Test
    void disablingThePanelAlsoDisablesCapture() {
        runner.withPropertyValues("bootui.panels.resilience.enabled=false")
                .run(context -> assertThat(
                                context.getBean(ResilienceEventRecorder.class).isEnabled())
                        .isFalse());
    }

    @Configuration(proxyBeanMethods = false)
    static class SampleRegistryConfiguration {

        @Bean
        CircuitBreakerRegistry circuitBreakerRegistry() {
            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofSeconds(5))
                    .build());
            registry.circuitBreaker("orders");
            return registry;
        }
    }
}
