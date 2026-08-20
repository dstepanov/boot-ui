package io.github.jdubois.bootui.autoconfigure.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Reads Resilience4j thread-pool bulkheads from the application's {@code ThreadPoolBulkheadRegistry} and
 * subscribes BootUI's metadata-only consumers to each bulkhead's native event publisher.
 *
 * <p>Loaded only when {@code resilience4j-bulkhead} is on the classpath (see
 * {@link Resilience4jPolicyProvider}).</p>
 */
final class Resilience4jThreadPoolBulkheadReader implements Resilience4jRegistryReader {

    private final ObjectProvider<?> registryProvider;
    private final Set<String> capturing = ConcurrentHashMap.newKeySet();

    Resilience4jThreadPoolBulkheadReader(ObjectProvider<?> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public boolean available() {
        return registry() != null;
    }

    private ThreadPoolBulkheadRegistry registry() {
        return (ThreadPoolBulkheadRegistry) Resilience4jRegistryReader.uniqueBean(registryProvider);
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        ThreadPoolBulkheadRegistry registry = registry();
        if (registry == null) {
            return List.of();
        }
        List<ResiliencePolicyDto> policies = new ArrayList<>();
        for (ThreadPoolBulkhead bulkhead : registry.getAllBulkheads()) {
            policies.add(toPolicy(bulkhead));
        }
        return policies;
    }

    private static ResiliencePolicyDto toPolicy(ThreadPoolBulkhead bulkhead) {
        ThreadPoolBulkheadConfig config = bulkhead.getBulkheadConfig();

        ResilienceSettings settings = new ResilienceSettings()
                .addUnknownProvenance("coreThreadPoolSize", config.getCoreThreadPoolSize())
                .addUnknownProvenance("maxThreadPoolSize", config.getMaxThreadPoolSize())
                .add("queueCapacity", config.getQueueCapacity(), 100)
                .add("keepAliveDuration", config.getKeepAliveDuration(), java.time.Duration.ofMillis(20));

        return new ResiliencePolicyDto(
                bulkhead.getName(),
                ResilienceVocabulary.TYPE_BULKHEAD,
                ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                ResilienceVocabulary.SOURCE_REGISTRY,
                null,
                null,
                settings.build(),
                ResiliencePolicyMetricsDto.none());
    }

    @Override
    public void registerCapture(ResilienceEventRecorder recorder) {
        ThreadPoolBulkheadRegistry registry = registry();
        if (registry == null) {
            return;
        }
        registry.getEventPublisher().onEntryAdded(event -> subscribe(event.getAddedEntry(), recorder));
        for (ThreadPoolBulkhead bulkhead : registry.getAllBulkheads()) {
            subscribe(bulkhead, recorder);
        }
    }

    /** Only rejections are captured, for the same buffer-pressure reason as the semaphore bulkhead. */
    private void subscribe(ThreadPoolBulkhead bulkhead, ResilienceEventRecorder recorder) {
        if (bulkhead == null || !capturing.add(bulkhead.getName())) {
            return;
        }
        bulkhead.getEventPublisher()
                .onCallRejected(event -> recorder.record(
                        event.getBulkheadName(),
                        ResilienceVocabulary.TYPE_BULKHEAD,
                        ResilienceVocabulary.PROVIDER_RESILIENCE4J,
                        null,
                        ResilienceVocabulary.OUTCOME_REJECTED,
                        null,
                        null,
                        null));
    }
}
