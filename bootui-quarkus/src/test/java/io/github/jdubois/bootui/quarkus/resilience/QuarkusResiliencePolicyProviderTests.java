package io.github.jdubois.bootui.quarkus.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicySettingDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.jdubois.bootui.quarkus.StubConfig;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test of the Quarkus resilience binding: build-time-captured SmallRye Fault Tolerance annotations
 * are mapped onto the shared contract, MicroProfile configuration overrides win over the captured literal
 * with honest {@code CONFIGURED} provenance, and a breaker SmallRye cannot name reports {@code UNKNOWN}
 * rather than a guessed {@code CLOSED}.
 */
class QuarkusResiliencePolicyProviderTests {

    private static RawResiliencePolicy circuitBreaker(String className, String methodName, String breakerName) {
        return new RawResiliencePolicy(
                breakerName == null ? className + "#" + methodName : breakerName,
                ResilienceVocabulary.TYPE_CIRCUIT_BREAKER,
                "CircuitBreaker",
                className,
                methodName,
                breakerName,
                List.of(
                        new RawResilienceSetting(
                                "requestVolumeThreshold", "20", ResilienceVocabulary.PROVENANCE_DEFAULT),
                        new RawResilienceSetting("failureRatio", "0.5", ResilienceVocabulary.PROVENANCE_DEFAULT)));
    }

    private static RawResiliencePolicy retry(String className, String methodName) {
        return new RawResiliencePolicy(
                className + "#" + methodName,
                ResilienceVocabulary.TYPE_RETRY,
                "Retry",
                className,
                methodName,
                null,
                List.of(new RawResilienceSetting("maxRetries", "3", ResilienceVocabulary.PROVENANCE_DEFAULT)));
    }

    private static QuarkusResiliencePolicyProvider provider(
            List<RawResiliencePolicy> policies, QuarkusCircuitBreakerStates states, Map<String, String> config) {
        return new QuarkusResiliencePolicyProvider(
                policies == null
                        ? new UnsatisfiedInstance<>()
                        : new SatisfiedInstance<>(new QuarkusResiliencePolicies(policies)),
                states == null ? new UnsatisfiedInstance<>() : new SatisfiedInstance<>(states),
                new StubConfig(config));
    }

    private static Optional<ResiliencePolicySettingDto> setting(ResiliencePolicyDto policy, String name) {
        return policy.settings().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst();
    }

    private static QuarkusCircuitBreakerStates states(Map<String, String> byName) {
        return new QuarkusCircuitBreakerStates() {
            @Override
            public String state(String name) {
                return name == null ? null : byName.get(name);
            }

            @Override
            public void onStateChange(String name, Consumer<String> listener) {}
        };
    }

    @Test
    void reportsUnavailableWhenTheFaultToleranceExtensionIsAbsent() {
        QuarkusResiliencePolicyProvider provider = provider(null, null, Map.of());

        assertThat(provider.providerId()).isEqualTo(ResilienceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE);
        assertThat(provider.available()).isFalse();
        assertThat(provider.policies()).isEmpty();
    }

    @Test
    void mapsCapturedAnnotationsOntoTheSharedContract() {
        QuarkusResiliencePolicyProvider provider =
                provider(List.of(retry("com.example.OrderService", "place")), null, Map.of());

        assertThat(provider.available()).isTrue();
        ResiliencePolicyDto policy = provider.policies().get(0);
        assertThat(policy.type()).isEqualTo(ResilienceVocabulary.TYPE_RETRY);
        assertThat(policy.provider()).isEqualTo(ResilienceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE);
        assertThat(policy.source()).isEqualTo(ResilienceVocabulary.SOURCE_ANNOTATION);
        assertThat(policy.target()).isEqualTo("com.example.OrderService#place");
        assertThat(policy.state()).isNull();
        assertThat(setting(policy, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("3");
            assertThat(retries.provenance()).isEqualTo(ResilienceVocabulary.PROVENANCE_DEFAULT);
        });
    }

    @Test
    void reportsNoCountersBecauseSmallRyeExposesNone() {
        ResiliencePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, Map.of())
                .policies()
                .get(0);

        assertThat(policy.metrics().successfulCalls()).isNull();
        assertThat(policy.metrics().failedCalls()).isNull();
        assertThat(policy.metrics().retriedCalls()).isNull();
    }

    @Test
    void prefersTheMostSpecificMicroProfileConfigurationOverride() {
        Map<String, String> config = new HashMap<>();
        config.put("Retry/maxRetries", "9");
        config.put("com.example.OrderService/Retry/maxRetries", "8");
        config.put("com.example.OrderService/place/Retry/maxRetries", "7");

        ResiliencePolicyDto policy = provider(List.of(retry("com.example.OrderService", "place")), null, config)
                .policies()
                .get(0);

        assertThat(setting(policy, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("7");
            assertThat(retries.provenance()).isEqualTo(ResilienceVocabulary.PROVENANCE_CONFIGURED);
        });
    }

    @Test
    void fallsBackToTheClassAndThenTheGlobalOverrideKey() {
        ResiliencePolicyDto classScoped = provider(
                        List.of(retry("com.example.OrderService", "place")),
                        null,
                        Map.of("com.example.OrderService/Retry/maxRetries", "8", "Retry/maxRetries", "9"))
                .policies()
                .get(0);
        assertThat(setting(classScoped, "maxRetries"))
                .get()
                .extracting(ResiliencePolicySettingDto::value)
                .isEqualTo("8");

        ResiliencePolicyDto globalScoped = provider(
                        List.of(retry("com.example.OrderService", "place")), null, Map.of("Retry/maxRetries", "9"))
                .policies()
                .get(0);
        assertThat(setting(globalScoped, "maxRetries")).get().satisfies(retries -> {
            assertThat(retries.value()).isEqualTo("9");
            assertThat(retries.provenance()).isEqualTo(ResilienceVocabulary.PROVENANCE_CONFIGURED);
        });
    }

    @Test
    void readsLiveBreakerStateForANamedBreaker() {
        ResiliencePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", "payments")),
                        states(Map.of("payments", ResilienceVocabulary.STATE_OPEN)),
                        Map.of())
                .policies()
                .get(0);

        assertThat(policy.name()).isEqualTo("payments");
        assertThat(policy.state()).isEqualTo(ResilienceVocabulary.STATE_OPEN);
    }

    @Test
    void reportsUnknownStateForAnAnonymousBreakerRatherThanGuessingClosed() {
        ResiliencePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", null)), states(Map.of()), Map.of())
                .policies()
                .get(0);

        assertThat(policy.state()).isEqualTo(ResilienceVocabulary.STATE_UNKNOWN);
    }

    @Test
    void reportsUnknownStateWhenTheStateSeamItselfIsAbsent() {
        ResiliencePolicyDto policy = provider(
                        List.of(circuitBreaker("com.example.PayService", "charge", "payments")), null, Map.of())
                .policies()
                .get(0);

        assertThat(policy.state()).isEqualTo(ResilienceVocabulary.STATE_UNKNOWN);
    }

    private static final class UnsatisfiedInstance<T> implements Instance<T> {

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return true;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            throw new UnsatisfiedResolutionException("no bean produced in this test");
        }
    }

    private static final class SatisfiedInstance<T> implements Instance<T> {

        private final T value;

        SatisfiedInstance(T value) {
            this.value = value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance.Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Instance.Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get() {
            return value;
        }
    }
}
