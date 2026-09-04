package io.github.jdubois.bootui.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;

/**
 * Micronaut bean condition that gates every BootUI console bean on
 * {@link BootUiMicronautActivationResolver}.
 *
 * <p>This is the Micronaut analogue of the Quarkus extension's {@code LaunchMode != NORMAL} build-step
 * gate and of the Spring adapter's {@code BootUiActivationCondition}: when it does not match, no
 * controller, filter, engine service or SPI provider is created at all, so production stays dark by
 * construction rather than by a runtime check inside each endpoint. The one deliberate exception is
 * {@link BootUiProdShellGuardFilter}, which is always registered so the static console shell shipped in
 * {@code bootui-ui} answers 404 rather than serving an inert SPA.
 *
 * <p>A bean context that is not an {@link ApplicationContext} has no environment to evaluate, so the
 * condition <em>fails closed</em> and BootUI stays dark.
 */
public final class BootUiEnabledCondition implements Condition {

    @Override
    @SuppressWarnings("rawtypes")
    public boolean matches(ConditionContext context) {
        BeanContext beanContext = context.getBeanContext();
        if (!(beanContext instanceof ApplicationContext applicationContext)) {
            context.fail("BootUI needs an ApplicationContext to resolve its activation state");
            return false;
        }
        BootUiMicronautActivation activation =
                BootUiMicronautActivationResolver.resolve(applicationContext.getEnvironment());
        if (!activation.enabled()) {
            context.fail(activation.reason());
            return false;
        }
        return true;
    }
}
