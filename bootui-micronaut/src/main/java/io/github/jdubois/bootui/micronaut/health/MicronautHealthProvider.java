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
 * <p><strong>Shape of the aggregated result.</strong> {@code DefaultHealthAggregator} does not flatten each
 * indicator into a map: it keys the root result's details map by indicator name and stores a nested
 * {@link HealthResult} <em>object</em> as the value, whose {@link HealthResult#getStatus() status} and
 * {@link HealthResult#getDetails() details} are the indicator's own. That nested result's
 * {@link HealthResult#getName() name} is the <em>application</em> name, not the indicator's, so the map key
 * is the only source for a component's name. Mapping only {@code Map} values — as this provider first did —
 * therefore sent every real indicator down the scalar fallback, publishing {@code UNKNOWN} components whose
 * "details" were the whole result object with the true status buried inside it. The mapping below handles
 * {@link HealthResult} values first, so a component's status is the indicator's own and its details are the
 * indicator's own details only, matching what the Spring provider produces from an
 * {@code IndicatedHealthDescriptor} and the Quarkus one from a MicroProfile check. The shared UI then
 * renders all three identically.
 *
 * <p>A {@link HealthStatus} can also carry a {@link HealthStatus#getDescription() description}, which is
 * dropped here: {@link HealthNodeDto} is a cross-adapter contract with no field for it, and folding it into
 * {@code details} would make a Micronaut component's details disagree with every other stack's. The Spring
 * provider drops Actuator's equivalent {@code Status} description for the same reason.
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
     * Maps an aggregated result onto the neutral tree. Package-private and {@code static} so it can be
     * unit-tested against a hand-built {@link HealthResult} tree without booting a context, exactly as the
     * Quarkus provider's {@code map} is tested against a hand-built SmallRye report.
     *
     * <p>The root is always the composite: its own details map <em>is</em> the per-indicator index, so it
     * becomes the components list and the root itself carries no details, matching the Spring provider's
     * treatment of a {@code CompositeHealthDescriptor}.
     */
    static HealthNodeDto map(HealthResult result) {
        return new HealthNodeDto("application", statusName(result.getStatus()), null, components(result.getDetails()));
    }

    /**
     * Maps a composite node's details map onto child components. Every entry becomes a component, because at
     * this position the map is an index of indicators rather than one indicator's data.
     */
    private static List<HealthNodeDto> components(Object details) {
        if (!(details instanceof Map<?, ?> detailsMap)) {
            return List.of();
        }
        List<HealthNodeDto> components = new ArrayList<>();
        for (Map.Entry<?, ?> entry : detailsMap.entrySet()) {
            components.add(toNode(String.valueOf(entry.getKey()), entry.getValue()));
        }
        return List.copyOf(components);
    }

    /**
     * Maps one entry of a composite's details map onto a component, over the three shapes a value can take.
     *
     * <ul>
     *   <li>A nested {@link HealthResult} — what {@code DefaultHealthAggregator} actually stores. Status and
     *       details are the indicator's own.</li>
     *   <li>A plain {@code Map} carrying a {@code status} key — the shape an indicator that assembles its own
     *       result map reports, and the shape a deserialized result takes.</li>
     *   <li>Anything else — a scalar, or a map that is plainly not a health result. Kept as the component's
     *       details under {@code UNKNOWN} rather than dropped, so an unexpected payload is visible rather
     *       than silently lost.</li>
     * </ul>
     */
    private static HealthNodeDto toNode(String name, Object value) {
        if (value instanceof HealthResult result) {
            Object details = result.getDetails();
            List<HealthNodeDto> children = nestedComponents(details);
            return new HealthNodeDto(
                    name, statusName(result.getStatus()), children.isEmpty() ? detailsValue(details) : null, children);
        }
        if (value instanceof Map<?, ?> component && component.containsKey("status")) {
            return new HealthNodeDto(
                    name, String.valueOf(component.get("status")), detailsValue(component.get("details")), List.of());
        }
        return new HealthNodeDto(name, HealthStatus.UNKNOWN.getName(), detailsValue(value), List.of());
    }

    /**
     * Maps an <em>indicator's own</em> details onto child components, which exist only for a composite
     * indicator — one built through {@link HealthAggregator#aggregate(String, Publisher)}, whose details map
     * holds further {@link HealthResult}s. Only those entries become children.
     *
     * <p>The distinction from {@link #components(Object)} is what keeps an ordinary indicator intact: a disk
     * space indicator's {@code {total, free, threshold}} details are data, not components, and mapping them
     * like an index would publish three bogus {@code UNKNOWN} children instead of one details table.
     */
    private static List<HealthNodeDto> nestedComponents(Object details) {
        if (!(details instanceof Map<?, ?> detailsMap)) {
            return List.of();
        }
        List<HealthNodeDto> children = new ArrayList<>();
        for (Map.Entry<?, ?> entry : detailsMap.entrySet()) {
            if (entry.getValue() instanceof HealthResult) {
                children.add(toNode(String.valueOf(entry.getKey()), entry.getValue()));
            }
        }
        return List.copyOf(children);
    }

    /**
     * Normalizes a details value for the wire. A map is copied with {@code String} keys and stable iteration
     * order, because Micronaut assembles details into plain {@code HashMap}s with arbitrary key types;
     * anything else is passed through as the scalar it is.
     */
    private static Object detailsValue(Object details) {
        if (details instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), value));
            return copy;
        }
        return details;
    }

    private static String statusName(HealthStatus status) {
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
