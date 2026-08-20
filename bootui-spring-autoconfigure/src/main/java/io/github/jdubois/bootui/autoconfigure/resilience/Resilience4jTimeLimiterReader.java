package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j time limiters from the application's {@code TimeLimiterRegistry} and subscribes
 * BootUI's metadata-only consumers to each limiter's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-timelimiter} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jTimeLimiterReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jTimeLimiterReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private TimeLimiterRegistry registry() {
        return (TimeLimiterRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        TimeLimiterRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        for (TimeLimiter limiter : registry.getAllTimeLimiters()) {
            policies.add(toPolicy(limiter));
        }
        return policies;
    }

    private static ResiliencePolicyDto toPolicy(TimeLimiter limiter) {
        ResilienceSettings settings = new ResilienceSettings()
                .add("timeoutDuration", limiter.getTimeLimiterConfig().getTimeoutDuration(), Duration.ofSeconds(1));

        return new ResiliencePolicyDto(
                limiter.getName(),
                ResilienceVocabulary.TYPE_TIME_LIMITER,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                ResilienceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                ResiliencePolicyMetricsDto.none());
    }

    @Override
    public void registerCapture(ResilienceEventRecorder recorder) {
        TimeLimiterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        registry.getEventPublisher().onEntryAdded(event -> subscribe(event.getAddedEntry(), recorder));
        for (TimeLimiter limiter : registry.getAllTimeLimiters()) {
            subscribe(limiter, recorder);
        }
    }

    /** Only timeouts are captured; successes fire on every completed call. */
    private void subscribe(TimeLimiter limiter, ResilienceEventRecorder recorder) {
        if (limiter == null || !capturing.add(limiter.getName())) {
            return;
        }
        limiter.getEventPublisher()
                .onTimeout(event -> recorder.record(
                        event.getTimeLimiterName(),
                        ResilienceVocabulary.TYPE_TIME_LIMITER,
                        ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        ResilienceVocabulary.OUTCOME_TIMEOUT,
                        null,
                        null,
                        null));
    }
}
