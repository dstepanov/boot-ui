package io.github.jdubois.bootui.quarkus.resilience;

import java.util.List;

/**
 * Build-time-captured holder for the host application's MicroProfile Fault Tolerance policies, produced by
 * {@link ResiliencePoliciesRecorder} and exposed as a synthetic CDI bean by the deployment processor only when
 * the {@code smallrye-fault-tolerance} capability is present and the launch mode is non-production.
 *
 * <p>{@link QuarkusResiliencePolicyProvider} injects an {@code Instance<QuarkusResiliencePolicies>}: an
 * unsatisfied instance means fault tolerance is not on the classpath, so the provider reports itself
 * unavailable and the panel renders {@code resiliencePresent=false}. The holder exists (rather than injecting
 * a raw {@code List}) so it is an unambiguous synthetic-bean type.</p>
 *
 * @param policies the captured policies, in Jandex discovery order (the engine applies the stable sort)
 */
public record QuarkusResiliencePolicies(List<RawResiliencePolicy> policies) {

    public QuarkusResiliencePolicies(List<RawResiliencePolicy> policies) {
        this.policies = policies == null ? List.of() : List.copyOf(policies);
    }
}
