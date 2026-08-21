package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j retries from the application's {@code RetryRegistry} and subscribes BootUI's
 * metadata-only consumers to each retry's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-retry} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jRetryReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jRetryReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private RetryRegistry registry() {
        return (RetryRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        RetryRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        for (Retry retry : registry.getAllRetries()) {
            policies.add(toPolicy(retry));
        }
        return policies;
    }

    private static ResiliencePolicyDto toPolicy(Retry retry) {
        RetryConfig config = retry.getRetryConfig();
        Retry.Metrics metrics = retry.getMetrics();

        ResilienceSettings settings = new ResilienceSettings()
                .add("maxAttempts", config.getMaxAttempts(), RetryConfig.DEFAULT_MAX_ATTEMPTS)
                .addUnknownProvenance("firstRetryDelay", firstRetryDelay(config))
                .add("failAfterMaxAttempts", config.isFailAfterMaxAttempts(), Boolean.FALSE);

        long retried =
                metrics.getNumberOfSuccessfulCallsWithRetryAttempt() + metrics.getNumberOfFailedCallsWithRetryAttempt();

        return new ResiliencePolicyDto(
                retry.getName(),
                ResilienceVocabulary.TYPE_RETRY,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                ResilienceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                new ResiliencePolicyMetricsDto(
                        metrics.getNumberOfSuccessfulCallsWithoutRetryAttempt()
                                + metrics.getNumberOfSuccessfulCallsWithRetryAttempt(),
                        metrics.getNumberOfFailedCallsWithoutRetryAttempt()
                                + metrics.getNumberOfFailedCallsWithRetryAttempt(),
                        retried,
                        null,
                        null,
                        null,
                        null,
                        null));
    }

    /**
     * Resilience4j models the retry delay as an interval function, so BootUI reports the first attempt's
     * interval with unknown provenance rather than guessing whether a custom backoff was configured.
     */
    private static String firstRetryDelay(RetryConfig config) {
        try {
            return config.getIntervalFunction().apply(1) + " ms";
        } catch (RuntimeException ex) {
            return null;
        }
    }

    @Override
    public void registerCapture(ResilienceEventRecorder recorder) {
        RetryRegistry registry = registry();
        if (registry == null) {
            return;
        }
        Resilience4jRegistryReader.registerRegistryCapture(
                registry.getEventPublisher(),
                registry.getAllRetries(),
                entry -> subscribe(entry, recorder),
                entry -> forget(entry));
    }

    private void subscribe(Retry retry, ResilienceEventRecorder recorder) {
        if (retry == null || !capturing.add(retry.getName())) {
            return;
        }
        retry.getEventPublisher()
                .onRetry(event -> recorder.record(
                        event.getName(),
                        ResilienceVocabulary.TYPE_RETRY,
                        ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        ResilienceVocabulary.OUTCOME_RETRY,
                        event.getNumberOfRetryAttempts(),
                        event.getWaitInterval() == null
                                ? null
                                : event.getWaitInterval().toMillis(),
                        ResilienceVocabulary.failureCategory(event.getLastThrowable())))
                .onError(event -> recorder.record(
                        event.getName(),
                        ResilienceVocabulary.TYPE_RETRY,
                        ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        ResilienceVocabulary.OUTCOME_RETRY_EXHAUSTED,
                        event.getNumberOfRetryAttempts(),
                        null,
                        ResilienceVocabulary.failureCategory(event.getLastThrowable())));
    }

    /**
     * Drops the name guard for an entry the registry no longer holds, so a later entry registered
     * under the same name is subscribed again instead of being mistaken for one already captured.
     */
    private void forget(Retry retry) {
        if (retry != null) {
            capturing.remove(retry.getName());
        }
    }
}
