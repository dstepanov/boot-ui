package io.github.jdubois.bootui.micronaut;

import io.micronaut.context.annotation.Requires;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation applied to every BootUI bean that must exist only while the console is active.
 *
 * <p>It is a single named alias for {@code @Requires(condition = BootUiEnabledCondition.class)} so the
 * activation gate is stated once and cannot be forgotten or spelled differently on one of the adapter's
 * many beans — the Micronaut analogue of the Quarkus extension registering all console beans from one
 * launch-mode-gated build step.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD})
@Requires(condition = BootUiEnabledCondition.class)
public @interface RequiresBootUi {}
