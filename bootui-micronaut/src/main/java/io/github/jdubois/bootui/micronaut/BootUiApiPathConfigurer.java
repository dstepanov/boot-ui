package io.github.jdubois.bootui.micronaut;

import io.github.jdubois.bootui.core.BootUiPathNormalizer;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextConfigurer;
import io.micronaut.context.annotation.ContextConfigurer;
import io.micronaut.context.env.Environment;
import io.micronaut.context.env.PropertySource;
import java.util.Map;

/**
 * Derives {@code bootui.api-path} from {@code bootui.path} so that moving the console is a one-key change,
 * exactly as it is on Spring Boot and Quarkus.
 *
 * <p>On those adapters the API mount is composed in Java, from the UI mount, at the point the routes are
 * registered. Micronaut instead resolves a {@code @Controller} path from a property placeholder, and a
 * placeholder default cannot reference another property: {@code ${bootui.api-path:${bootui.path:/bootui}/api}}
 * is not nested-aware in Micronaut 4 — the resolver splits on the first {@code &#125;} and yields the literal
 * {@code /bootui/api&#125;}. The console's API controllers would therefore stay behind at {@code /bootui/api}
 * while its UI moved, producing a shell that loads and then fails every call. Rather than make operators
 * set both keys, this contributes the derived value as a real property, early enough that the controllers'
 * placeholders resolve to it.
 *
 * <p>The hook is Micronaut's own {@link ApplicationContextConfigurer#configure(ApplicationContext)}, which
 * runs after the {@link Environment} has started (so {@code bootui.path} is readable from every property
 * source, including {@code application.yml}, environment variables and system properties) and before any
 * bean definition is loaded — hence before a controller's annotation metadata is resolved and cached
 * against the environment. It applies to every way a context is created: {@code Micronaut.run}, a bare
 * {@code ApplicationContext.run}, and {@code @MicronautTest}.
 *
 * <p>Two deliberate choices keep this honest:
 *
 * <ul>
 *   <li>Nothing is contributed when {@code bootui.api-path} is already set, and the contributed property
 *       source sits at {@value #ORDER} — below {@code application.yml} ({@code -300}), the environment
 *       ({@code -200}) and system properties ({@code -100}) — so an explicit value always wins, even after
 *       a context refresh.</li>
 *   <li>An invalid {@code bootui.path} contributes nothing and throws nothing. Failing here would surface
 *       as a context-configuration error; {@link BootUiPathsValidator} instead reports it at startup with
 *       the normalizer's own actionable message.</li>
 * </ul>
 *
 * <p>This is not a BootUI bean and so carries no {@link RequiresBootUi} gate — an
 * {@link ApplicationContextConfigurer} is loaded by the service loader before the bean container exists,
 * and cannot be conditioned on one. That is safe because the contribution is inert: it only writes the
 * value the console would have defaulted to anyway, and when the console is dark no route, filter or bean
 * ever reads it.
 */
@ContextConfigurer
public class BootUiApiPathConfigurer implements ApplicationContextConfigurer {

    /** The name the derived mount is contributed under, so it is identifiable in the Configuration panel. */
    static final String PROPERTY_SOURCE_NAME = "bootui-derived-api-path";

    /**
     * Lower than every property source an application configures ({@code application.yml} is {@code -300}),
     * so this contribution is a floor and never shadows a value the operator actually set.
     */
    static final int ORDER = -400;

    @Override
    public void configure(ApplicationContext applicationContext) {
        Environment environment = applicationContext.getEnvironment();
        if (environment.containsProperty(MicronautBootUiPaths.API_PATH_KEY)) {
            return;
        }
        String configuredUiPath = environment
                .getProperty(MicronautBootUiPaths.PATH_KEY, String.class)
                .orElse(null);
        String apiPath;
        try {
            apiPath = configuredUiPath == null
                    ? MicronautBootUiPaths.DEFAULT_API_PATH
                    : BootUiPathNormalizer.normalize(configuredUiPath) + "/api";
        } catch (IllegalArgumentException invalidUiPath) {
            return;
        }
        environment.addPropertySource(
                PropertySource.of(PROPERTY_SOURCE_NAME, Map.of(MicronautBootUiPaths.API_PATH_KEY, apiPath), ORDER));
    }
}
