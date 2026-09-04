package io.github.jdubois.bootui.micronaut;

import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;

/**
 * Resolves the type a bean definition should be <em>reported</em> as.
 *
 * <p>Micronaut applies AOP advice — {@code @Retryable}, {@code @Transactional}, {@code @Cacheable} and the
 * rest — by generating an intercepting subclass at compile time, and it is that subclass which appears in
 * the bean container. Reporting it verbatim would show the developer
 * {@code $FlakyService$Definition$Intercepted} where they wrote {@code FlakyService}: technically true,
 * useless in practice, and actively confusing in a panel whose job is to describe the application.
 *
 * <p>Every place this adapter enumerates bean definitions — the Beans inventory, the scheduled-task and
 * fault-tolerance inventories, the error-contract catalogue — resolves the type through here, so a bean is
 * named the same way everywhere regardless of whether advice happens to be woven onto it.
 */
public final class MicronautBeanTypes {

    private MicronautBeanTypes() {}

    /**
     * The application-facing type of a definition: the advised type for a generated interception proxy, the
     * definition's own type otherwise.
     */
    public static Class<?> resolve(BeanDefinition<?> definition) {
        if (definition == null) {
            return null;
        }
        if (definition instanceof ProxyBeanDefinition<?> proxy) {
            Class<?> target = proxy.getTargetType();
            if (target != null) {
                return target;
            }
        }
        return definition.getBeanType();
    }
}
