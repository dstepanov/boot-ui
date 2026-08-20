package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.jdubois.bootui.spi.ResiliencePolicyProvider;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Discovers Spring Retry's {@code @Retryable} declarations by inspecting bean types.
 *
 * <p>Spring Retry keeps no registry and exposes no runtime state, so BootUI reports the declared policy —
 * the attempt budget and backoff the developer wrote — and leaves {@code state} and the aggregate counters
 * empty rather than inventing values. Live outcomes for these policies come from
 * {@link BootUiRetryListener} instead.</p>
 *
 * <p>Bean types are resolved without forcing instantiation, and the scan result is memoized because the set
 * of {@code @Retryable} declarations is fixed once the context has started.</p>
 */
public class SpringRetryPolicyProvider implements ResiliencePolicyProvider {

    private final ConfigurableListableBeanFactory beanFactory;

    private volatile List<ResiliencePolicyDto> cached;

    public SpringRetryPolicyProvider(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public String providerId() {
        return ResilienceVocabulary.PROVIDER_SPRING_RETRY;
    }

    @Override
    public boolean available() {
        return !policies().isEmpty();
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        List<ResiliencePolicyDto> snapshot = cached;
        if (snapshot == null) {
            snapshot = scan();
            cached = snapshot;
        }
        return snapshot;
    }

    private List<ResiliencePolicyDto> scan() {
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> type = beanType(beanName);
            if (type == null) {
                continue;
            }
            collect(type, policies, seen);
        }
        return List.copyOf(policies);
    }

    private Class<?> beanType(String beanName) {
        try {
            // `false` keeps factory beans from being instantiated: inspecting the console must never start
            // application beans earlier than the application intended.
            Class<?> type = beanFactory.getType(beanName, false);
            return type == null ? null : ClassUtils.getUserClass(type);
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static void collect(Class<?> type, List<ResiliencePolicyDto> policies, Set<String> seen) {
        Retryable typeLevel = AnnotatedElementUtils.findMergedAnnotation(type, Retryable.class);
        for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
            Retryable retryable = AnnotatedElementUtils.findMergedAnnotation(method, Retryable.class);
            if (retryable == null) {
                retryable = typeLevel;
            }
            if (retryable == null || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String target = type.getSimpleName() + "#" + method.getName();
            String name = retryable.label().isBlank() ? target : retryable.label();
            if (!seen.add(name + "|" + target)) {
                continue;
            }
            policies.add(toPolicy(name, target, retryable));
        }
    }

    private static ResiliencePolicyDto toPolicy(String name, String target, Retryable retryable) {
        Backoff backoff = retryable.backoff();
        ResilienceSettings settings = new ResilienceSettings()
                .add("maxAttempts", maxAttempts(retryable), 3)
                .add("backoffDelay", backoffDelay(backoff), "1000 ms")
                .add("stateful", retryable.stateful(), Boolean.FALSE);
        if (backoff.multiplier() > 0) {
            settings.addConfigured("backoffMultiplier", backoff.multiplier());
        }
        if (!retryable.recover().isBlank()) {
            settings.addConfigured("recover", retryable.recover());
        }

        return new ResiliencePolicyDto(
                name,
                ResilienceVocabulary.TYPE_RETRY,
                ResilienceVocabulary.PROVIDER_SPRING_RETRY,
                ResilienceVocabulary.SOURCE_ANNOTATION,
                target,
                null,
                settings.build(),
                ResiliencePolicyMetricsDto.none());
    }

    /**
     * Reports the literal attempt budget, or the unevaluated SpEL expression when one was used. BootUI never
     * evaluates an application expression, because doing so would run application code on a console read.
     */
    private static Object maxAttempts(Retryable retryable) {
        return retryable.maxAttemptsExpression().isBlank()
                ? retryable.maxAttempts()
                : retryable.maxAttemptsExpression();
    }

    /** Mirrors Spring Retry's own resolution of {@code @Backoff}: {@code delay} wins, then {@code value}. */
    private static String backoffDelay(Backoff backoff) {
        if (!backoff.delayExpression().isBlank()) {
            return backoff.delayExpression();
        }
        long delay = backoff.delay() > 0 ? backoff.delay() : backoff.value();
        return (delay > 0 ? delay : 1000L) + " ms";
    }
}
