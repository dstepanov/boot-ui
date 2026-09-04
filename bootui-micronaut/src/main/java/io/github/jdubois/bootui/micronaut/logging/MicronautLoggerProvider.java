package io.github.jdubois.bootui.micronaut.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.spi.LoggerProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * Micronaut {@link LoggerProvider} backed by Logback, the logging backend Micronaut applications use by
 * default (and the one {@code micronaut-logging}'s own {@code LogbackLoggingSystem} drives).
 *
 * <p>It is the Micronaut analogue of the Spring adapter's {@code SpringLoggerProvider} (which sits on
 * Actuator's {@code LoggersEndpoint}) and of the Quarkus adapter's {@code QuarkusLoggerProvider} (which
 * sits on the JBoss LogManager). The framework-neutral concerns — self-logger filtering, sorting, paging
 * and the write guard — live in the shared engine {@code LoggersService}, so every adapter shares them and
 * this class only returns raw neutral data and performs the backend call.
 *
 * <p>Logback is an <em>optional</em> dependency of this adapter (compile-only, {@code provided} scope): an
 * application on another SLF4J backend simply reports {@link #available()} {@code false} and the panel
 * renders honest setup guidance instead of failing. Every backend interaction is wrapped fail-soft, so any
 * {@link LinkageError} (the backend jar absent) or {@link RuntimeException} degrades to "no backend
 * available" (empty report / rejected write) rather than failing the panel.
 *
 * <p><strong>Level vocabulary.</strong> The shared UI binds to BootUI's canonical levels
 * ({@code OFF, FATAL, ERROR, WARN, INFO, DEBUG, TRACE}), reported in the same descending-severity order the
 * Spring Actuator endpoint returns. Logback has no {@code FATAL} level of its own — it maps {@code FATAL}
 * onto {@code ERROR} — so a logger configured at {@code ERROR} reads back as {@code ERROR}, and setting
 * {@code FATAL} is accepted and applied as {@code ERROR}, which is exactly what Logback would do with it.
 */
public final class MicronautLoggerProvider implements LoggerProvider {

    /**
     * The canonical level vocabulary in descending severity, matching the order Spring's Actuator
     * {@code LoggersEndpoint} returns (a descending {@code TreeSet} of {@code LogLevel}) so the shared UI's
     * level dropdown renders identically on every platform.
     */
    static final List<String> LEVELS = List.of("OFF", "FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE");

    /** Display name BootUI (and Spring's Actuator) uses for the root logger. */
    private static final String ROOT_DISPLAY_NAME = "ROOT";

    @Override
    public boolean available() {
        return loggerContext() != null;
    }

    @Override
    public LoggersReport rawLoggers() {
        LoggerContext context = loggerContext();
        if (context == null) {
            return new LoggersReport(List.of(), List.of());
        }
        try {
            List<LoggerDto> loggers = new ArrayList<>();
            for (Logger logger : context.getLoggerList()) {
                loggers.add(new LoggerDto(
                        displayName(logger.getName()),
                        canonicalName(logger.getLevel()),
                        canonicalName(logger.getEffectiveLevel())));
            }
            return new LoggersReport(LEVELS, loggers);
        } catch (LinkageError | RuntimeException ex) {
            return new LoggersReport(List.of(), List.of());
        }
    }

    @Override
    public LoggerDto setLevel(String name, String level) {
        LoggerContext context = loggerContext();
        if (context == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            Logger logger = context.getLogger(backendName(name));
            if (logger == null) {
                return null;
            }
            logger.setLevel(toLogbackLevel(level));
            return new LoggerDto(
                    displayName(logger.getName()),
                    canonicalName(logger.getLevel()),
                    canonicalName(logger.getEffectiveLevel()));
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /**
     * The live Logback context, or {@code null} when Logback is not the SLF4J backend (or is absent from
     * the classpath entirely, in which case the class reference itself would fail to link).
     */
    private static LoggerContext loggerContext() {
        try {
            ILoggerFactory factory = LoggerFactory.getILoggerFactory();
            return factory instanceof LoggerContext context ? context : null;
        } catch (LinkageError | RuntimeException ex) {
            return null;
        }
    }

    /** Renders Logback's root logger under the same {@code ROOT} display name every adapter uses. */
    private static String displayName(String backendName) {
        return org.slf4j.Logger.ROOT_LOGGER_NAME.equals(backendName) ? ROOT_DISPLAY_NAME : backendName;
    }

    /** The inverse of {@link #displayName}: resolves the {@code ROOT} display name back to Logback's own. */
    private static String backendName(String displayName) {
        return ROOT_DISPLAY_NAME.equals(displayName) ? org.slf4j.Logger.ROOT_LOGGER_NAME : displayName;
    }

    /**
     * Maps a Logback level onto BootUI's canonical vocabulary. A logger with no explicitly configured level
     * reports {@code null}, which the engine renders as "inherited", matching Spring and Quarkus.
     */
    private static String canonicalName(Level level) {
        if (level == null) {
            return null;
        }
        return switch (level.toInt()) {
            case Level.OFF_INT -> "OFF";
            case Level.ERROR_INT -> "ERROR";
            case Level.WARN_INT -> "WARN";
            case Level.INFO_INT -> "INFO";
            case Level.DEBUG_INT -> "DEBUG";
            case Level.TRACE_INT -> "TRACE";
            case Level.ALL_INT -> "TRACE";
            default -> level.toString().toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Maps a canonical level name onto a Logback level. {@code null} or a blank value clears the
     * configured level (restoring inheritance), matching the Actuator contract the shared UI binds to;
     * {@code FATAL} is applied as {@code ERROR}, Logback's own mapping for it.
     */
    private static Level toLogbackLevel(String level) {
        if (level == null || level.isBlank()) {
            return null;
        }
        String canonical = level.trim().toUpperCase(Locale.ROOT);
        return switch (canonical) {
            case "OFF" -> Level.OFF;
            case "FATAL", "ERROR" -> Level.ERROR;
            case "WARN" -> Level.WARN;
            case "INFO" -> Level.INFO;
            case "DEBUG" -> Level.DEBUG;
            case "TRACE" -> Level.TRACE;
            default -> throw new IllegalArgumentException("Unsupported log level: " + level);
        };
    }
}
