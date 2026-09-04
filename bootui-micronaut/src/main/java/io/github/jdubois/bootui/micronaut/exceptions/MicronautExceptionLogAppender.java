package io.github.jdubois.bootui.micronaut.exceptions;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.spi.TraceIdProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;

/**
 * Logback appender that records every logged {@link Throwable} into the shared engine
 * {@link ExceptionStore} behind the Exceptions panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusExceptionLogHandler}. Capturing at the
 * logging seam rather than at a framework error hook is deliberate and matches the other adapters: it
 * catches failures wherever they surface — an unhandled route error, a scheduled job, a background thread —
 * not only the ones that happen to reach an HTTP error handler.
 *
 * <p>Request context (method and path) is read from Micronaut's {@link ServerRequestContext} when the
 * failure happened on a request thread, so a captured exception is attributable to an endpoint. BootUI's own
 * traffic and loggers are never captured, and a thread-local re-entrancy guard means a failure raised
 * <em>inside</em> capture cannot recurse.
 */
public final class MicronautExceptionLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String SOURCE = "log";

    private final ExceptionStore store;
    private final InternalPackageMatcher internalPackages;
    private final Environment environment;
    private final BeanContext beanContext;
    private final ThreadLocal<Boolean> capturing = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public MicronautExceptionLogAppender(
            ExceptionStore store,
            InternalPackageMatcher internalPackages,
            Environment environment,
            BeanContext beanContext) {
        this.store = store;
        this.internalPackages = internalPackages;
        this.environment = environment;
        this.beanContext = beanContext;
        setName("bootui-exceptions");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null || Boolean.TRUE.equals(capturing.get())) {
            return;
        }
        Throwable thrown = throwableOf(event);
        if (thrown == null || internalPackages.matchesName(event.getLoggerName())) {
            return;
        }
        capturing.set(Boolean.TRUE);
        try {
            HttpRequest<?> request = ServerRequestContext.currentRequest().orElse(null);
            String path = request == null ? null : request.getPath();
            if (MicronautBootUiPaths.isBootUiRequest(environment, path)) {
                return; // never capture BootUI's own traffic
            }
            String method = request == null ? null : request.getMethodName();
            store.record(thrown, event.getThreadName(), method, path, null, SOURCE, currentTraceId());
        } catch (RuntimeException ex) {
            // Diagnostics capture must never interfere with the application's logging.
        } finally {
            capturing.set(Boolean.FALSE);
        }
    }

    /**
     * The real {@link Throwable} behind a logging event, or {@code null} when the event carries none or only
     * a serialized proxy (which happens for events replayed from a remote appender and carries no stack to
     * fingerprint).
     */
    private static Throwable throwableOf(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        return proxy instanceof ThrowableProxy throwableProxy ? throwableProxy.getThrowable() : null;
    }

    private String currentTraceId() {
        try {
            return beanContext
                    .findBean(TraceIdProvider.class)
                    .map(TraceIdProvider::currentTraceId)
                    .orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
