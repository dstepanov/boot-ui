package io.github.jdubois.bootui.micronaut.faulttolerance;

import io.github.jdubois.bootui.core.dto.FaultTolerancePolicyDto;
import io.github.jdubois.bootui.core.dto.FaultTolerancePolicySettingDto;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.micronaut.MicronautBeanTypes;
import io.github.jdubois.bootui.spi.FaultTolerancePolicyProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.retry.annotation.CircuitBreaker;
import io.micronaut.retry.annotation.Retryable;
import java.util.ArrayList;
import java.util.List;

/**
 * Micronaut {@link FaultTolerancePolicyProvider}: inventories the application's {@code @Retryable} and
 * {@code @CircuitBreaker} policies for the Fault Tolerance panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's SmallRye Fault Tolerance reader. Micronaut applies both
 * annotations through compile-time AOP and records them in its bean metadata, so the inventory is read live
 * with no capture step and includes a policy that has never fired.
 *
 * <p>Live circuit state is deliberately <em>not</em> claimed: Micronaut's circuit breaker keeps its state
 * inside the generated interceptor and exposes no maintenance API to query it, so the panel reports the
 * configured policy and leaves state unknown rather than guessing. State transitions are still visible —
 * {@code MicronautRetryEventCapture} records them from the framework's own circuit events as they happen.
 *
 * <p>BootUI's own beans are skipped through {@link MicronautBeanTypes#isBootUiOwned(BeanDefinition)}, the
 * self-filter shared with the Beans, scheduled-task and error-contract inventories, so a policy declared by
 * the console — or by an engine service one of this adapter's {@code @Factory} classes produces — is never
 * reported as the application's.
 */
public final class MicronautRetryPolicyProvider implements FaultTolerancePolicyProvider {

    /** The provider id the shared UI groups these policies under. */
    public static final String PROVIDER_ID = "micronaut-retry";

    static final String TYPE_RETRY = FaultToleranceVocabulary.TYPE_RETRY;
    static final String TYPE_CIRCUIT_BREAKER = FaultToleranceVocabulary.TYPE_CIRCUIT_BREAKER;

    private static final String SOURCE_ANNOTATION = FaultToleranceVocabulary.SOURCE_ANNOTATION;

    private final BeanContext beanContext;

    public MicronautRetryPolicyProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean available() {
        return beanContext != null;
    }

    @Override
    public List<FaultTolerancePolicyDto> policies() {
        if (beanContext == null) {
            return List.of();
        }
        List<FaultTolerancePolicyDto> policies = new ArrayList<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            if (MicronautBeanTypes.isBootUiOwned(definition)) {
                continue;
            }
            Class<?> beanType = MicronautBeanTypes.resolve(definition);
            if (beanType == null) {
                continue;
            }
            collect(definition, beanType, policies);
        }
        return List.copyOf(policies);
    }

    private static void collect(
            BeanDefinition<?> definition, Class<?> beanType, List<FaultTolerancePolicyDto> policies) {
        // A class-level annotation applies to every method of the bean, which is how Micronaut applies it too.
        AnnotationValue<CircuitBreaker> typeCircuitBreaker = definition.getAnnotation(CircuitBreaker.class);
        AnnotationValue<Retryable> typeRetryable = definition.getAnnotation(Retryable.class);
        for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
            AnnotationValue<CircuitBreaker> circuitBreaker = method.getAnnotation(CircuitBreaker.class) != null
                    ? method.getAnnotation(CircuitBreaker.class)
                    : typeCircuitBreaker;
            if (circuitBreaker != null) {
                policies.add(circuitBreakerPolicy(beanType, method, circuitBreaker));
                continue;
            }
            AnnotationValue<Retryable> retryable = method.getAnnotation(Retryable.class) != null
                    ? method.getAnnotation(Retryable.class)
                    : typeRetryable;
            if (retryable != null) {
                policies.add(retryPolicy(beanType, method, retryable));
            }
        }
    }

    private static FaultTolerancePolicyDto circuitBreakerPolicy(
            Class<?> beanType, ExecutableMethod<?, ?> method, AnnotationValue<CircuitBreaker> annotation) {
        List<FaultTolerancePolicySettingDto> settings = new ArrayList<>();
        addSetting(settings, "attempts", annotation.stringValue("attempts").orElse(null));
        addSetting(settings, "delay", annotation.stringValue("delay").orElse(null));
        addSetting(settings, "maxDelay", annotation.stringValue("maxDelay").orElse(null));
        addSetting(settings, "multiplier", annotation.stringValue("multiplier").orElse(null));
        addSetting(settings, "reset", annotation.stringValue("reset").orElse(null));
        return new FaultTolerancePolicyDto(
                target(beanType, method),
                TYPE_CIRCUIT_BREAKER,
                PROVIDER_ID,
                SOURCE_ANNOTATION,
                target(beanType, method),
                null,
                List.copyOf(settings),
                null);
    }

    private static FaultTolerancePolicyDto retryPolicy(
            Class<?> beanType, ExecutableMethod<?, ?> method, AnnotationValue<Retryable> annotation) {
        List<FaultTolerancePolicySettingDto> settings = new ArrayList<>();
        addSetting(settings, "attempts", annotation.stringValue("attempts").orElse(null));
        addSetting(settings, "delay", annotation.stringValue("delay").orElse(null));
        addSetting(settings, "maxDelay", annotation.stringValue("maxDelay").orElse(null));
        addSetting(settings, "multiplier", annotation.stringValue("multiplier").orElse(null));
        return new FaultTolerancePolicyDto(
                target(beanType, method),
                TYPE_RETRY,
                PROVIDER_ID,
                SOURCE_ANNOTATION,
                target(beanType, method),
                null,
                List.copyOf(settings),
                null);
    }

    private static void addSetting(List<FaultTolerancePolicySettingDto> settings, String name, String value) {
        if (value != null && !value.isBlank()) {
            settings.add(
                    new FaultTolerancePolicySettingDto(name, value, FaultToleranceVocabulary.PROVENANCE_CONFIGURED));
        }
    }

    static String target(Class<?> beanType, ExecutableMethod<?, ?> method) {
        return beanType.getName() + "#" + method.getMethodName();
    }
}
