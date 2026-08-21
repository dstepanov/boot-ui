package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j rate limiters from the application's {@code RateLimiterRegistry} and subscribes
 * BootUI's metadata-only consumers to each limiter's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-ratelimiter} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jRateLimiterReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jRateLimiterReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private RateLimiterRegistry registry() {
        return (RateLimiterRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        RateLimiterRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        for (RateLimiter limiter : registry.getAllRateLimiters()) {
            policies.add(toPolicy(limiter));
        }
        return policies;
    }

    private static ResiliencePolicyDto toPolicy(RateLimiter limiter) {
        RateLimiterConfig config = limiter.getRateLimiterConfig();
        RateLimiter.Metrics metrics = limiter.getMetrics();

        ResilienceSettings settings = new ResilienceSettings()
                .add("limitForPeriod", config.getLimitForPeriod(), 50)
                .add("limitRefreshPeriod", config.getLimitRefreshPeriod(), Duration.ofNanos(500L))
                .add("timeoutDuration", config.getTimeoutDuration(), Duration.ofSeconds(5))
                .add("availablePermissions", metrics.getAvailablePermissions(), null)
                .add("waitingThreads", metrics.getNumberOfWaitingThreads(), null);

        return new ResiliencePolicyDto(
                limiter.getName(),
                ResilienceVocabulary.TYPE_RATE_LIMITER,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                ResilienceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                ResiliencePolicyMetricsDto.none());
    }

    @Override
    public void registerCapture(ResilienceEventRecorder recorder) {
        RateLimiterRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllRateLimiters(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    /**
     * Only rejections are captured. A rate limiter's success event fires on every permitted call, which on a
     * busy application would flood the bounded buffer and push out the diagnostic events that matter.
     */
    private void subscribe(RateLimiter limiter, ResilienceEventRecorder recorder) {
        if (limiter == null || !capturing.add(limiter.getName())) {
            return;
        }
        limiter.getEventPublisher()
                .onFailure(event -> recorder.record(
                        event.getRateLimiterName(),
                        ResilienceVocabulary.TYPE_RATE_LIMITER,
                        ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        ResilienceVocabulary.OUTCOME_REJECTED,
                        null,
                        null,
                        null));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(RateLimiter limiter) {
        if (limiter != null) {
            capturing.remove(limiter.getName());
        }
    }
}
