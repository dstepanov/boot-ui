package io.github.jdubois.bootui.micronaut.scheduled;

import io.github.jdubois.bootui.core.dto.ScheduledTaskDto;
import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.github.jdubois.bootui.micronaut.MicronautBeanTypes;
import io.github.jdubois.bootui.spi.ScheduledTaskProvider;
import io.micronaut.context.BeanContext;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.convert.ConversionService;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ExecutableMethod;
import io.micronaut.scheduling.annotation.Scheduled;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Micronaut {@link ScheduledTaskProvider}: inventories the application's {@code @Scheduled} methods for the
 * Scheduled Tasks panel.
 *
 * <p>The Micronaut analogue of the Spring adapter's {@code ScheduledTaskHolder} reader and the Quarkus
 * adapter's build-time {@code @Scheduled} capture. Micronaut records every executable method's annotation
 * metadata in its compile-time bean definitions, so the inventory is read live with no capture step — and,
 * unlike a runtime-registry reader, it sees a task whether or not it has run yet.
 *
 * <p>Each task is reported under the trigger Micronaut actually used: a {@code cron} expression, a
 * {@code fixedRate}, or a {@code fixedDelay}, with the initial delay alongside. BootUI's own scheduled work
 * is filtered out so the panel describes the application.
 */
public final class MicronautScheduledTaskProvider implements ScheduledTaskProvider {

    static final String TRIGGER_CRON = "CRON";
    static final String TRIGGER_FIXED_RATE = "FIXED_RATE";
    static final String TRIGGER_FIXED_DELAY = "FIXED_DELAY";
    static final String TRIGGER_UNKNOWN = "UNKNOWN";

    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

    private final BeanContext beanContext;

    public MicronautScheduledTaskProvider(BeanContext beanContext) {
        this.beanContext = beanContext;
    }

    @Override
    public boolean available() {
        // Micronaut's scheduler is part of micronaut-context, so the capability is always present; a task
        // list that is simply empty is an honest answer, not an unavailable panel.
        return beanContext != null;
    }

    @Override
    public List<ScheduledTaskDto> tasks() {
        if (beanContext == null) {
            return List.of();
        }
        List<ScheduledTaskDto> tasks = new ArrayList<>();
        for (BeanDefinition<?> definition : beanContext.getAllBeanDefinitions()) {
            Class<?> beanType = MicronautBeanTypes.resolve(definition);
            if (beanType == null || INTERNAL_PACKAGES.matchesName(beanType.getName())) {
                continue;
            }
            for (ExecutableMethod<?, ?> method : definition.getExecutableMethods()) {
                for (AnnotationValue<Scheduled> scheduled : method.getAnnotationValuesByType(Scheduled.class)) {
                    tasks.add(toDto(beanType, method, scheduled));
                }
            }
        }
        return List.copyOf(tasks);
    }

    private static ScheduledTaskDto toDto(
            Class<?> beanType, ExecutableMethod<?, ?> method, AnnotationValue<Scheduled> scheduled) {
        String runnable = beanType.getName() + "." + method.getMethodName();
        String cron = text(scheduled, "cron");
        String fixedRate = text(scheduled, "fixedRate");
        String fixedDelay = text(scheduled, "fixedDelay");
        Long initialDelayMs = durationMs(text(scheduled, "initialDelay"));

        if (cron != null) {
            return new ScheduledTaskDto(runnable, TRIGGER_CRON, cron, initialDelayMs, null);
        }
        if (fixedRate != null) {
            return new ScheduledTaskDto(runnable, TRIGGER_FIXED_RATE, fixedRate, initialDelayMs, "MILLISECONDS");
        }
        if (fixedDelay != null) {
            return new ScheduledTaskDto(runnable, TRIGGER_FIXED_DELAY, fixedDelay, initialDelayMs, "MILLISECONDS");
        }
        return new ScheduledTaskDto(runnable, TRIGGER_UNKNOWN, null, initialDelayMs, null);
    }

    private static String text(AnnotationValue<Scheduled> scheduled, String member) {
        return scheduled
                .stringValue(member)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElse(null);
    }

    /**
     * Micronaut accepts both ISO-8601 durations ({@code PT1M}) and its own shorthand ({@code 30s}) here. The
     * panel reports milliseconds, so the ISO form is tried first and Micronaut's own conversion second; a
     * value neither understands degrades to {@code null} rather than to a wrong number.
     */
    private static Long durationMs(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Duration.parse(value).toMillis();
        } catch (RuntimeException ex) {
            try {
                return ConversionService.SHARED
                        .convert(value, Duration.class)
                        .map(Duration::toMillis)
                        .orElse(null);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }
}
