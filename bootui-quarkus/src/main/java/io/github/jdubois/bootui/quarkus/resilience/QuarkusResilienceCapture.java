package io.github.jdubois.bootui.quarkus.resilience;

import io.github.jdubois.bootui.engine.resilience.ResilienceEventRecorder;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Wires SmallRye Fault Tolerance's circuit-breaker state changes into the bounded, metadata-only
 * {@link ResilienceEventRecorder} that backs both the Resilience panel's event list and Live Activity's
 * {@code RESILIENCE} entries.
 *
 * <p>State transitions are the only resilience events SmallRye exposes to an observer: unlike Resilience4j it
 * publishes no per-call retry/rejection/timeout stream, so the Quarkus adapter reports exactly what the
 * library supports rather than wrapping application beans or replacing interceptors to synthesize the rest.
 * Only breakers the application named with {@code @CircuitBreakerName} can be observed, which is why the
 * build-time scan records that name.</p>
 *
 * <p>Registration happens once at startup and is entirely fail-open: a breaker SmallRye does not know is
 * skipped, and the listener body cannot throw into the library because {@link ResilienceEventRecorder#record}
 * swallows its own failures. When capture is disabled ({@code bootui.resilience.enabled=false} or the panel
 * is off) the recorder is disabled, so no listener is registered at all.</p>
 */
@Dependent
public class QuarkusResilienceCapture {

    private static final Logger LOG = Logger.getLogger(QuarkusResilienceCapture.class);

    private final Instance<QuarkusResiliencePolicies> capturedPolicies;

    private final Instance<QuarkusCircuitBreakerStates> circuitBreakerStates;

    private final ResilienceEventRecorder recorder;

    @Inject
    public QuarkusResilienceCapture(
            Instance<QuarkusResiliencePolicies> capturedPolicies,
            Instance<QuarkusCircuitBreakerStates> circuitBreakerStates,
            ResilienceEventRecorder recorder) {
        this.capturedPolicies = capturedPolicies;
        this.circuitBreakerStates = circuitBreakerStates;
        this.recorder = recorder;
    }

    void onStart(@Observes StartupEvent event) {
        if (!recorder.isEnabled() || capturedPolicies.isUnsatisfied() || circuitBreakerStates.isUnsatisfied()) {
            return;
        }
        QuarkusCircuitBreakerStates states = circuitBreakerStates.get();
        Set<String> registered = new LinkedHashSet<>();
        for (RawResiliencePolicy policy : capturedPolicies.get().policies()) {
            if (!ResilienceVocabulary.TYPE_CIRCUIT_BREAKER.equals(policy.type())
                    || policy.circuitBreakerName().isBlank()
                    || !registered.add(policy.circuitBreakerName())) {
                continue;
            }
            String name = policy.name();
            String target = policy.target();
            states.onStateChange(
                    policy.circuitBreakerName(),
                    state -> recorder.recordStateTransition(
                            name,
                            ResilienceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE,
                            target.isBlank() ? null : target,
                            state));
        }
        LOG.debugf("BootUI observes %d named circuit breaker(s)", registered.size());
    }
}
