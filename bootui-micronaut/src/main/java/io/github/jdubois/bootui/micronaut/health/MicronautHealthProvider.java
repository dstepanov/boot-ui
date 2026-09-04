package io.github.jdubois.bootui.micronaut.health;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.spi.HealthProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.health.HealthStatus;
import io.micronaut.management.endpoint.health.HealthLevelOfDetail;
import io.micronaut.management.health.aggregator.HealthAggregator;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Micronaut {@link HealthProvider} backed by {@code micronaut-management}'s health aggregation.
 *
 * <p>The Micronaut analogue of the Spring adapter's Actuator-backed provider and of the Quarkus adapter's
 * {@code QuarkusHealthProvider} (SmallRye Health): it asks the application's own
 * {@link HealthAggregator} to aggregate every registered {@link HealthIndicator} and maps the result onto
 * the neutral {@link HealthNodeDto} tree the shared engine {@code HealthService} renders. Nothing is read
 * over HTTP — the aggregation runs in-process, so the panel works whether or not the {@code /health}
 * endpoint is exposed.
 *
 * <p>Details are requested at {@link HealthLevelOfDetail#STATUS_DESCRIPTION_DETAILS}: BootUI is
 * localhost-only and every value it renders still passes through {@code SecretMasker} behind the live
 * exposure policy, so the panel shows the same depth a developer would get from the endpoint with
 * {@code details-visible} enabled, without asking them to widen the application's own exposure.
 *
 * <p>The aggregation is reactive; this provider is called from a blocking controller thread, so it
 * subscribes and waits with a bounded timeout. A timeout or error degrades to an {@code UNKNOWN} root
 * rather than failing the panel.
 */
public final class MicronautHealthProvider implements HealthProvider {

    /** Bound on the in-process aggregation so a wedged indicator cannot hang the panel. */
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HealthAggregator<HealthResult> aggregator;
    private final HealthIndicator[] indicators;

    public MicronautHealthProvider(HealthAggregator<HealthResult> aggregator, List<HealthIndicator> indicators) {
        this.aggregator = aggregator;
        this.indicators = indicators == null ? new HealthIndicator[0] : indicators.toArray(new HealthIndicator[0]);
    }

    /**
     * Builds a provider over the application's own health aggregation, or returns {@code null} when
     * micronaut-management published no {@link HealthAggregator} — which happens when the health endpoint
     * is switched off. Returning {@code null} lets the engine render the honest "not available" guidance
     * rather than an empty tree.
     *
     * <p>This factory is the single place the management API is touched, so
     * {@code BootUiEngineFactory} can gate the whole binding on one class-presence probe and an
     * application without micronaut-management never links these types.
     */
    @SuppressWarnings("unchecked")
    public static HealthProvider create(BeanContext beanContext) {
        HealthAggregator<HealthResult> aggregator = (HealthAggregator<HealthResult>)
                beanContext.findBean(HealthAggregator.class).orElse(null);
        if (aggregator == null) {
            return null;
        }
        return new MicronautHealthProvider(aggregator, List.copyOf(beanContext.getBeansOfType(HealthIndicator.class)));
    }

    @Override
    public HealthNodeDto readRoot() {
        HealthResult result =
                awaitFirst(aggregator.aggregate(indicators, HealthLevelOfDetail.STATUS_DESCRIPTION_DETAILS));
        return result == null ? unknown() : map(result);
    }

    private static HealthNodeDto unknown() {
        return new HealthNodeDto("application", HealthStatus.UNKNOWN.getName(), null, List.of());
    }

    /**
     * Maps an aggregated result onto the neutral tree. Micronaut nests each indicator's own result inside
     * the root result's details map (keyed by indicator name), which is exactly the shape the shared UI
     * renders as child components.
     */
    static HealthNodeDto map(HealthResult result) {
        return new HealthNodeDto("application", statusName(result), null, components(result.getDetails()));
    }

    private static List<HealthNodeDto> components(Object details) {
        if (!(details instanceof Map<?, ?> detailsMap)) {
            return List.of();
        }
        List<HealthNodeDto> components = new ArrayList<>();
        for (Map.Entry<?, ?> entry : detailsMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> component) {
                components.add(new HealthNodeDto(
                        name, String.valueOf(component.get("status")), detailsOf(component), List.of()));
            } else {
                components.add(new HealthNodeDto(name, HealthStatus.UNKNOWN.getName(), value, List.of()));
            }
        }
        return List.copyOf(components);
    }

    private static Object detailsOf(Map<?, ?> component) {
        Object details = component.get("details");
        if (details instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return details;
    }

    private static String statusName(HealthResult result) {
        HealthStatus status = result.getStatus();
        return status == null ? HealthStatus.UNKNOWN.getName() : status.getName();
    }

    /**
     * Subscribes to a publisher and returns its first element, or {@code null} on error, completion
     * without an element, or timeout. Implemented directly against the Reactive Streams API so the adapter
     * needs no reactive-library dependency of its own.
     */
    private static <T> T awaitFirst(Publisher<T> publisher) {
        if (publisher == null) {
            return null;
        }
        AtomicReference<T> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(new Subscriber<T>() {
            @Override
            public void onSubscribe(Subscription subscription) {
                subscription.request(1);
            }

            @Override
            public void onNext(T item) {
                value.compareAndSet(null, item);
                done.countDown();
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });
        try {
            if (!done.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }
        return value.get();
    }
}
