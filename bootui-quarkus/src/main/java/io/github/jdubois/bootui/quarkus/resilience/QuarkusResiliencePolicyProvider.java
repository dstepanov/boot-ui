package io.github.jdubois.bootui.quarkus.resilience;

import io.github.jdubois.bootui.core.dto.ResiliencePolicyDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicyMetricsDto;
import io.github.jdubois.bootui.core.dto.ResiliencePolicySettingDto;
import io.github.jdubois.bootui.engine.resilience.ResilienceVocabulary;
import io.github.jdubois.bootui.spi.ResiliencePolicyProvider;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;

/**
 * Quarkus {@link ResiliencePolicyProvider} over the build-time-captured {@link QuarkusResiliencePolicies}
 * holder, reporting the application's MicroProfile / SmallRye Fault Tolerance policies.
 *
 * <p>The deployment processor exposes the synthetic {@code QuarkusResiliencePolicies} bean only when the
 * {@code smallrye-fault-tolerance} capability is present and the launch mode is non-production. This provider
 * is therefore wired unconditionally but tolerates the bean's absence: an unsatisfied {@code Instance} means
 * no fault-tolerance extension is on the classpath, so {@link #available()} is {@code false} and the engine
 * renders {@code resiliencePresent=false}. It names no {@code io.smallrye.faulttolerance} type itself; live
 * breaker state is read through the neutral {@link QuarkusCircuitBreakerStates} seam, which is likewise
 * optional.</p>
 *
 * <p>Each captured annotation member is re-resolved against MicroProfile Fault Tolerance's configuration
 * override keys before it is reported, so a threshold changed in {@code application.properties} shows its
 * live value with {@code CONFIGURED} provenance instead of the annotation's now-stale literal.</p>
 *
 * <p>Reading a report performs no protected call and no state transition. SmallRye publishes no per-policy
 * invocation counters of its own, so every metric is reported as {@code null} — explicitly "not exposed by
 * this library" rather than a guessed zero.</p>
 */
@Singleton
public class QuarkusResiliencePolicyProvider implements ResiliencePolicyProvider {

    private final Instance<QuarkusResiliencePolicies> capturedPolicies;

    private final Instance<QuarkusCircuitBreakerStates> circuitBreakerStates;

    private final Config config;

    @Inject
    public QuarkusResiliencePolicyProvider(
            Instance<QuarkusResiliencePolicies> capturedPolicies,
            Instance<QuarkusCircuitBreakerStates> circuitBreakerStates,
            Config config) {
        this.capturedPolicies = capturedPolicies;
        this.circuitBreakerStates = circuitBreakerStates;
        this.config = config;
    }

    @Override
    public String providerId() {
        return ResilienceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE;
    }

    @Override
    public boolean available() {
        return !capturedPolicies.isUnsatisfied();
    }

    @Override
    public List<ResiliencePolicyDto> policies() {
        if (capturedPolicies.isUnsatisfied()) {
            return List.of();
        }
        QuarkusCircuitBreakerStates states = circuitBreakerStates.isUnsatisfied() ? null : circuitBreakerStates.get();
        List<ResiliencePolicyDto> mapped = new ArrayList<>();
        for (RawResiliencePolicy raw : capturedPolicies.get().policies()) {
            mapped.add(toDto(raw, states));
        }
        return mapped;
    }

    private ResiliencePolicyDto toDto(RawResiliencePolicy raw, QuarkusCircuitBreakerStates states) {
        List<ResiliencePolicySettingDto> settings = new ArrayList<>();
        for (RawResilienceSetting setting : raw.settings()) {
            settings.add(resolve(raw, setting));
        }
        String target = raw.target();
        return new ResiliencePolicyDto(
                raw.name(),
                raw.type(),
                ResilienceVocabulary.PROVIDER_SMALLRYE_FAULT_TOLERANCE,
                ResilienceVocabulary.SOURCE_ANNOTATION,
                target.isBlank() ? null : target,
                state(raw, states),
                List.copyOf(settings),
                ResiliencePolicyMetricsDto.none());
    }

    /**
     * The setting's live value, preferring a MicroProfile Fault Tolerance configuration override over the
     * captured annotation literal. The three override keys are consulted most-specific first, exactly as the
     * specification defines them.
     */
    private ResiliencePolicySettingDto resolve(RawResiliencePolicy raw, RawResilienceSetting setting) {
        String suffix = "/" + raw.annotationName() + "/" + setting.name();
        String override = null;
        if (!raw.methodName().isBlank()) {
            override = configured(raw.className() + "/" + raw.methodName() + suffix);
        }
        if (override == null) {
            override = configured(raw.className() + suffix);
        }
        if (override == null) {
            override = configured(raw.annotationName() + "/" + setting.name());
        }
        if (override != null) {
            return new ResiliencePolicySettingDto(setting.name(), override, ResilienceVocabulary.PROVENANCE_CONFIGURED);
        }
        return new ResiliencePolicySettingDto(setting.name(), setting.value(), setting.provenance());
    }

    private String configured(String key) {
        try {
            Optional<String> value = config.getOptionalValue(key, String.class);
            return value.filter(candidate -> !candidate.isBlank()).orElse(null);
        } catch (RuntimeException ex) {
            // A malformed or expression-valued override must never break the panel.
            return null;
        }
    }

    /**
     * Live breaker state, or {@code UNKNOWN} when SmallRye cannot answer. SmallRye can only report the state
     * of a breaker the application named with {@code @CircuitBreakerName}, so an anonymous breaker is
     * explicitly unknown rather than assumed closed.
     */
    private String state(RawResiliencePolicy raw, QuarkusCircuitBreakerStates states) {
        if (!ResilienceVocabulary.TYPE_CIRCUIT_BREAKER.equals(raw.type())) {
            return null;
        }
        String resolved = states == null ? null : states.state(raw.circuitBreakerName());
        return resolved == null ? ResilienceVocabulary.STATE_UNKNOWN : resolved;
    }
}
