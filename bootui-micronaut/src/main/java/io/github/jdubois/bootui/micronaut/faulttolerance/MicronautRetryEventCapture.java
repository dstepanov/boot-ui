package io.github.jdubois.bootui.micronaut.faulttolerance;

import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceEventRecorder;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceVocabulary;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.retry.event.CircuitClosedEvent;
import io.micronaut.retry.event.CircuitOpenEvent;
import io.micronaut.retry.event.RetryEvent;
import jakarta.inject.Singleton;

/**
 * Records Micronaut's retry and circuit-breaker events into the shared engine
 * {@link FaultToleranceEventRecorder}, which is what gives the Fault Tolerance panel its timeline.
 *
 * <p>Micronaut publishes each retry attempt and every circuit state change as an ordinary application event,
 * so one listener is enough. This is also the only way BootUI can report live circuit state on Micronaut: the
 * breaker keeps its state inside the generated interceptor, so the panel learns about a transition by
 * observing it rather than by polling.
 */
@RequiresBootUi
@Requires(classes = RetryEvent.class)
@Singleton
public class MicronautRetryEventCapture implements ApplicationEventListener<Object> {

    private static final String STATE_OPEN = "OPEN";
    private static final String STATE_CLOSED = "CLOSED";

    private final FaultToleranceEventRecorder recorder;

    public MicronautRetryEventCapture(FaultToleranceEventRecorder recorder) {
        this.recorder = recorder;
    }

    @Override
    public boolean supports(Object event) {
        return event instanceof RetryEvent || event instanceof CircuitOpenEvent || event instanceof CircuitClosedEvent;
    }

    @Override
    public void onApplicationEvent(Object event) {
        try {
            if (event instanceof CircuitOpenEvent opened) {
                recordTransition(opened.getSource(), STATE_OPEN);
            } else if (event instanceof CircuitClosedEvent closed) {
                recordTransition(closed.getSource(), STATE_CLOSED);
            } else if (event instanceof RetryEvent retry) {
                recordRetry(retry);
            }
        } catch (RuntimeException ex) {
            // Capture is best-effort: it must never interfere with the application's retry behavior.
        }
    }

    private void recordTransition(ExecutableMethod<?, ?> method, String state) {
        String target = target(method);
        recorder.recordStateTransition(target, MicronautRetryPolicyProvider.PROVIDER_ID, target, state);
    }

    private void recordRetry(RetryEvent retry) {
        String target = target(retry.getSource());
        Throwable throwable = retry.getThrowable();
        recorder.record(
                target,
                FaultToleranceVocabulary.TYPE_RETRY,
                MicronautRetryPolicyProvider.PROVIDER_ID,
                target,
                FaultToleranceVocabulary.OUTCOME_RETRY,
                null,
                null,
                throwable == null ? null : throwable.getClass().getName());
    }

    private static String target(ExecutableMethod<?, ?> method) {
        if (method == null) {
            return "unknown";
        }
        return method.getDeclaringType().getName() + "#" + method.getMethodName();
    }
}
