package io.github.jdubois.bootui.micronaut.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.jdubois.bootui.core.dto.LogLineDto;
import io.github.jdubois.bootui.engine.logtail.LogTailBuffer;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;

/**
 * Logback appender that feeds the shared engine {@link LogTailBuffer} behind the Log Tail panel.
 *
 * <p>The Micronaut analogue of the Quarkus adapter's {@code QuarkusLogTailHandler} (a JUL handler on the
 * JBoss LogManager). It is attached to the root logger by {@link BootUiLogbackCapture} while the console is
 * active, and detached when the context shuts down.
 *
 * <p>BootUI's own loggers are skipped, so the panel never fills with the console describing itself — and,
 * more importantly, so a log line emitted while capturing cannot feed back into the capture.
 */
public final class MicronautLogTailAppender extends AppenderBase<ILoggingEvent> {

    /** Display name BootUI uses for the root logger, matching every other adapter. */
    private static final String ROOT_DISPLAY_NAME = "ROOT";

    private final LogTailBuffer buffer;
    private final InternalPackageMatcher internalPackages;

    public MicronautLogTailAppender(LogTailBuffer buffer, InternalPackageMatcher internalPackages) {
        this.buffer = buffer;
        this.internalPackages = internalPackages;
        setName("bootui-log-tail");
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (event == null) {
            return;
        }
        String logger = event.getLoggerName();
        if (internalPackages.matchesName(logger)) {
            return;
        }
        try {
            buffer.add(new LogLineDto(
                    event.getTimeStamp(),
                    event.getLevel() == null ? null : event.getLevel().toString(),
                    (logger == null || logger.isEmpty()) ? ROOT_DISPLAY_NAME : logger,
                    event.getFormattedMessage(),
                    event.getThreadName()));
        } catch (RuntimeException ex) {
            // Capture is best-effort and must never interfere with the application's logging.
        }
    }
}
