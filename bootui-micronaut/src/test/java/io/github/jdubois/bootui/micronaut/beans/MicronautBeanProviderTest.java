package io.github.jdubois.bootui.micronaut.beans;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.BeanSummary;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins the Beans panel's subject: the application, never the console describing it.
 *
 * <p>The self-filter used to look only at a bean's <em>type</em>, which hid this adapter's controllers and
 * filters but not the ~50 framework-neutral engine services its {@code @Factory} classes produce — those
 * are {@code io.github.jdubois.bootui.engine.*} types, outside the adapter's packages, and the panel listed
 * every one of them as an {@code APPLICATION} bean with injection edges into a graph the application never
 * wrote. A real context is the only thing that can prove they are gone, because the factories are what put
 * them there.
 *
 * <p>The other half of the contract — that the application's own beans are still listed, and still
 * classified {@code APPLICATION} — is pinned in {@code BootUiMicronautSampleAppTest}, which has an
 * application to assert about.
 */
class MicronautBeanProviderTest {

    private static final String BOOTUI_PACKAGE = "io.github.jdubois.bootui.";

    /**
     * Names an engine service from each of the factories, so a regression that reintroduces one family of
     * beans is not masked by the others still being hidden.
     */
    private static final List<String> ENGINE_SERVICE_NAMES =
            List.of("apiTokenAuthenticator", "aiUsageService", "cliService", "beansService", "configService");

    @Test
    void reportsNoBeanBootUiItselfOwns() {
        try (ApplicationContext context = ApplicationContext.run(Map.<String, Object>of(), "test")) {
            List<BeanSummary> beans = new MicronautBeanProvider(context).beans();

            assertThat(beans).isNotEmpty();
            assertThat(beans)
                    .as("the Beans panel describes the application, so no BootUI type may appear in it")
                    .noneMatch(bean -> bean.type() != null && bean.type().startsWith(BOOTUI_PACKAGE));
            assertThat(beans)
                    .extracting(BeanSummary::name)
                    .as("the engine services BootUI's own factories produce are console furniture, not beans"
                            + " the application declared")
                    .doesNotContainAnyElementsOf(ENGINE_SERVICE_NAMES);
        }
    }

    /**
     * Injection edges are name-based and are filtered to visible beans, so hiding BootUI's own beans must
     * also remove every edge that pointed at one — otherwise the graph would carry dangling references.
     */
    @Test
    void reportsNoInjectionEdgeIntoABeanBootUiItselfOwns() {
        try (ApplicationContext context = ApplicationContext.run(Map.<String, Object>of(), "test")) {
            List<BeanSummary> beans = new MicronautBeanProvider(context).beans();

            assertThat(beans)
                    .allSatisfy(bean -> assertThat(bean.dependencies())
                            .as("%s dependencies", bean.name())
                            .doesNotContainAnyElementsOf(ENGINE_SERVICE_NAMES));
        }
    }
}
