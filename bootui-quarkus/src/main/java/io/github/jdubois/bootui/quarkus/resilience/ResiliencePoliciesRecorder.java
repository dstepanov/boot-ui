package io.github.jdubois.bootui.quarkus.resilience;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import java.util.List;

/**
 * Quarkus recorder that replays the build-time-captured MicroProfile Fault Tolerance metadata into a runtime
 * {@link QuarkusResiliencePolicies} holder.
 *
 * <p>The deployment processor's {@code registerResiliencePolicies} build step scans the application's bean
 * archive for the MicroProfile Fault Tolerance annotations at build time, builds the
 * {@link RawResiliencePolicy} list, and calls {@link #create(List)} from a {@code @Record(STATIC_INIT)} step;
 * the returned {@link RuntimeValue} backs a synthetic {@link QuarkusResiliencePolicies} bean. This mirrors the
 * Scheduled Tasks panel's build-time-capture strategy, chosen for the same reason: the declared policy
 * metadata simply does not exist at runtime.</p>
 */
@Recorder
public class ResiliencePoliciesRecorder {

    /** Wraps the captured rows in a runtime holder backing the synthetic {@link QuarkusResiliencePolicies} bean. */
    public RuntimeValue<QuarkusResiliencePolicies> create(List<RawResiliencePolicy> policies) {
        return new RuntimeValue<>(new QuarkusResiliencePolicies(policies));
    }
}
