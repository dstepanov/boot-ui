package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ActionBusyResult;
import io.github.jdubois.bootui.engine.action.ActionBusyException;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;

/**
 * Maps the engine's framework-neutral single-flight rejection to the canonical BootUI HTTP response — the
 * Micronaut analogue of the Spring adapter's {@code ActionBusyExceptionHandler} and the Quarkus adapter's
 * {@code ActionBusyExceptionMapper}.
 *
 * <p>Without it a second concurrent scan is answered with the host framework's generic 500, which tells the
 * console nothing: "busy, try again" is a statement about the request, not a server fault, and the shared UI
 * branches on the 409 and the {@link ActionBusyResult} body to say so.
 *
 * <p>The handler is registered for BootUI's own exception type only, so it can never intercept a failure
 * raised by the host application, and it is gated on {@link RequiresBootUi} like every other console bean.
 */
@RequiresBootUi
@Singleton
@Produces
public class ActionBusyExceptionHandler implements ExceptionHandler<ActionBusyException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, ActionBusyException exception) {
        return HttpResponse.status(HttpStatus.CONFLICT).body(exception.result());
    }
}
