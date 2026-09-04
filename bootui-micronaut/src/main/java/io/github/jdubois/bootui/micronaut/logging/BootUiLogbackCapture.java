package io.github.jdubois.bootui.micronaut.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import io.github.jdubois.bootui.engine.exceptions.ExceptionStore;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.exceptions.MicronautExceptionLogAppender;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.env.Environment;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Attaches BootUI's Logback appenders to the root logger while the console is active, and detaches them
 * when the application context shuts down.
 *
 * <p>Both capture points — the Log Tail buffer and the Exceptions store — read from the same place, so they
 * are attached together here rather than each installing itself. That keeps installation and, just as
 * importantly, <em>removal</em> in one auditable place: a console that is torn down (a test context, a
 * refreshed context) must not leave appenders behind holding references to a dead buffer.
 *
 * <p>Logback is optional for this adapter. When the SLF4J backend is something else, this bean quietly does
 * nothing and both panels render empty — the same honest degradation the Loggers panel makes.
 *
 * <p>This is a {@link Context}-scoped bean so it is created during startup rather than on first request:
 * log lines and failures emitted before anyone opens the console still need to be captured.
 */
@RequiresBootUi
@Context
public class BootUiLogbackCapture {

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

    private final LogTailBuffer logTailBuffer;
    private final ExceptionStore exceptionStore;
    private final Environment environment;
    private final BeanContext beanContext;

    private final List<Appender<ch.qos.logback.classic.spi.ILoggingEvent>> attached = new ArrayList<>();

    public BootUiLogbackCapture(
            LogTailBuffer logTailBuffer,
            ExceptionStore exceptionStore,
            Environment environment,
            BeanContext beanContext) {
        this.logTailBuffer = logTailBuffer;
        this.exceptionStore = exceptionStore;
        this.environment = environment;
        this.beanContext = beanContext;
    }

    @PostConstruct
    void attach() {
        LoggerContext context = loggerContext();
        if (context == null) {
            return;
        }
        try {
            Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            attach(context, root, new MicronautLogTailAppender(logTailBuffer, INTERNAL_PACKAGES));
            attach(
                    context,
                    root,
                    new MicronautExceptionLogAppender(exceptionStore, INTERNAL_PACKAGES, environment, beanContext));
        } catch (LinkageError | RuntimeException ex) {
            // A logging backend that cannot be instrumented leaves both panels empty rather than failing
            // startup: diagnostics must never be the reason an application does not boot.
            detach();
        }
    }

    @PreDestroy
    void detach() {
        LoggerContext context = loggerContext();
        if (context != null) {
            try {
                Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
                attached.forEach(root::detachAppender);
            } catch (LinkageError | RuntimeException ex) {
                // Nothing further to do: the context is going away regardless.
            }
        }
        attached.forEach(appender -> {
            try {
                appender.stop();
            } catch (RuntimeException ex) {
                // Ignored: an appender that cannot stop cleanly must not fail shutdown.
            }
        });
        attached.clear();
    }

    private void attach(
            LoggerContext context, Logger root, Appender<ch.qos.logback.classic.spi.ILoggingEvent> appender) {
        appender.setContext(context);
        appender.start();
        root.addAppender(appender);
        attached.add(appender);
    }

    /** The live Logback context, or {@code null} when Logback is not the SLF4J backend. */
    private static LoggerContext loggerContext() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            return factory instanceof LoggerContext context ? context : null;
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
    }
}
