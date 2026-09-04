package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.engine.support.InternalPackageMatcher;
import io.micronaut.inject.BeanDefinition;
import io.micronaut.inject.ProxyBeanDefinition;
import java.util.List;

/**
 * Resolves the type a bean definition should be <em>reported</em> as, and decides whether a definition is
 * BootUI's own rather than the application's.
 *
 * <p>Micronaut applies AOP advice — {@code @Retryable}, {@code @Transactional}, {@code @Cacheable} and the
 * rest — by generating an intercepting subclass at compile time, and it is that subclass which appears in
 * the bean container. Reporting it verbatim would show the developer
 * {@code $FlakyService$Definition$Intercepted} where they wrote {@code FlakyService}: technically true,
 * useless in practice, and actively confusing in a panel whose job is to describe the application.
 *
 * <p>Every place this adapter enumerates bean definitions — the Beans inventory, the scheduled-task and
 * fault-tolerance inventories, the error-contract catalogue — resolves the type through here, so a bean is
 * named the same way everywhere regardless of whether advice happens to be woven onto it, and applies the
 * same self-filter, so the four inventories cannot drift on where BootUI ends and the application begins.
 *
 * <h2>Why the bean type alone is not enough</h2>
 *
 * <p>The self-filter is the Micronaut analogue of the Spring adapter's
 * {@code BootUiSelfDataFilter.isBootUiBean(beanName, type, resource)}, and it needs that filter's second
 * axis for the same reason Spring does. Scoping the matcher to {@code io.github.jdubois.bootui.micronaut}
 * and {@code …core} — rather than the whole {@code io.github.jdubois.bootui} tree — is deliberate: an
 * application that happens to live under that root package must not be swallowed by the console it embeds.
 * But BootUI's console is assembled by {@code @Factory} classes in this adapter
 * ({@code BootUiEngineFactory}, {@code BootUiMcpFactory}, {@code BootUiCliFactory},
 * {@code BootUiOtelFactory}, …) whose {@code @Singleton} methods return framework-neutral
 * {@code io.github.jdubois.bootui.engine} types, and neutral {@code …spi} ports where one is published.
 * Those ~50 beans — {@code beansService}, {@code configService}, {@code apiTokenAuthenticator} and the rest
 * — are as much the console's own furniture as its controllers are, yet a type-only check reports every one
 * of them to the user as an {@code APPLICATION} bean, complete with injection edges into a graph the
 * application never wrote.
 *
 * <p>Micronaut records the producing factory on a factory-built definition:
 * {@link BeanDefinition#getDeclaringType()} names the {@code @Factory} class, and for an ordinary
 * {@code @Singleton} class it names the bean type itself. So a definition is BootUI's own when
 * <em>either</em> its reported type or its declaring type is in the adapter's packages — which hides the
 * engine services without widening the package matcher to a root package an application may legitimately
 * share.
 */
public final class MicronautBeanTypes {

    /**
     * BootUI's own packages. Deliberately not the {@code io.github.jdubois.bootui} root: an application
     * living there is the user's, and the neutral {@code engine}/{@code spi} packages carry no bean
     * definitions of their own — they only reach the container through the factories above, which the
     * declaring-type check catches.
     */
    private static final InternalPackageMatcher INTERNAL_PACKAGES =
            new InternalPackageMatcher(List.of("io.github.jdubois.bootui.micronaut", "io.github.jdubois.bootui.core"));

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

    /**
     * Whether this definition is BootUI's own — either because the bean's reported type lives in the
     * adapter's packages, or because the {@code @Factory} that declares it does. Every inventory that walks
     * {@code BeanContext.getAllBeanDefinitions()} skips these, so the panels describe the application.
     */
    public static boolean isBootUiOwned(BeanDefinition<?> definition) {
        if (definition == null) {
            return false;
        }
        if (isBootUiType(resolve(definition))) {
            return true;
        }
        try {
            return isBootUiType(definition.getDeclaringType().orElse(null));
        } catch (RuntimeException | LinkageError ex) {
            // A definition whose declaring type cannot be resolved must not fail the whole inventory; the
            // bean-type check above already had its say.
            return false;
        }
    }

    /** Whether a class is BootUI's own, for the places that hold a type rather than a bean definition. */
    public static boolean isBootUiType(Class<?> type) {
        return type != null && isBootUiTypeName(type.getName());
    }

    /** Whether a fully-qualified type name is BootUI's own. */
    public static boolean isBootUiTypeName(String typeName) {
        return INTERNAL_PACKAGES.matchesName(typeName);
    }
}
