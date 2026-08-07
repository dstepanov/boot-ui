package io.github.jdubois.bootui.engine.action;

import io.github.jdubois.bootui.core.dto.ActionBusyResult;

/** Expected control-flow signal raised when a BootUI action loses single-flight admission. */
public final class ActionBusyException extends RuntimeException {

    private final ActionBusyResult result;

    ActionBusyException(ActionBusyResult result) {
        super(result.message(), null, false, false);
        this.result = result;
    }

    public ActionBusyResult result() {
        return result;
    }
}
