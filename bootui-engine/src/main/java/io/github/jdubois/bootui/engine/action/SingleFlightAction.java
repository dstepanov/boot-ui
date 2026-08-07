package io.github.jdubois.bootui.engine.action;

import io.github.jdubois.bootui.core.dto.ActionBusyResult;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Per-service, fail-fast admission for actions that must not overlap.
 *
 * <p>The caller keeps control of execution: the winning supplier runs synchronously on the caller's
 * existing thread. Losing callers never wait, queue, or invoke their supplier.</p>
 */
public final class SingleFlightAction {

    public static final String BUSY_ERROR = "BootUI action already in progress";

    private final AtomicReference<ActiveOperation> active = new AtomicReference<>();

    public <T> T run(String operation, Supplier<T> action) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(action, "action");

        ActiveOperation claim = new ActiveOperation(operation);
        while (!active.compareAndSet(null, claim)) {
            ActiveOperation current = active.get();
            if (current != null) {
                throw busy(operation, current.operation());
            }
        }

        try {
            return action.get();
        } finally {
            active.compareAndSet(claim, null);
        }
    }

    private static ActionBusyException busy(String operation, String activeOperation) {
        String message = "Operation '" + operation + "' cannot start while '" + activeOperation + "' is in progress.";
        return new ActionBusyException(new ActionBusyResult(BUSY_ERROR, operation, activeOperation, message));
    }

    private record ActiveOperation(String operation) {}
}
