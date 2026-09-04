package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.engine.safety.BootUiSecurityHeaders;
import io.github.jdubois.bootui.micronaut.web.BeansController;
import io.github.jdubois.bootui.micronaut.web.BootUiAssetsController;
import io.github.jdubois.bootui.micronaut.web.MicronautIndexController;
import io.github.jdubois.bootui.micronaut.web.OverviewController;
import io.github.jdubois.bootui.micronaut.web.PanelsController;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Boots a real embedded server the way a production deployment does — the {@code prod} environment
 * active — and pins that the console is dark <em>by construction</em>: not a runtime check inside an
 * endpoint that could be bypassed, but the absence of every gated bean plus a 404 from the
 * always-registered shell guard.
 *
 * <p>Micronaut deduces the {@code test} environment whenever the context is built from a JUnit stack, and
 * {@code test} is one of BootUI's default <em>enabled</em> environments. That collision is the point of
 * these tests: {@link BootUiMicronautActivationResolver} evaluates
 * {@code bootui.disabled-environments} first, so {@code prod} wins over the deduced {@code test} and the
 * console stays dark exactly as it would on a real {@code java -jar} production run.
 */
class BootUiMicronautProductionDarkTest {

    /** The whole console surface: the shell, the API, and the packaged SPA assets. */
    private static final List<String> CONSOLE_PATHS =
            List.of("/bootui", "/bootui/api/panels", "/bootui/api/overview", "/bootui/assets/x.js");

    /**
     * Every console bean the adapter can produce is gated: the controllers, the safety and panel-access
     * filters, the eager path validator, and the engine services {@code BootUiEngineFactory} produces.
     */
    private static final List<Class<?>> GATED_BEANS = List.of(
            MicronautIndexController.class,
            PanelsController.class,
            OverviewController.class,
            BeansController.class,
            BootUiAssetsController.class,
            BootUiMicronautSafetyFilter.class,
            MicronautPanelAccessFilter.class,
            BootUiPathsValidator.class,
            MicronautPanelAvailability.class,
            BeansService.class);

    @Test
    void answers404ForTheWholeConsoleSurfaceInTheProdEnvironment() {
        withServer(Map.of(), (client) -> {
            for (String path : CONSOLE_PATHS) {
                assertThat((Object) statusOf(client, path))
                        .as("GET %s with the prod environment active", path)
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }
        });
    }

    @Test
    void answers404ForTheWholeConsoleSurfaceWhenExplicitlyDisabled() {
        withServer(Map.of(BootUiMicronautActivationResolver.ENABLED_KEY, "OFF"), List.of(), (client) -> {
            for (String path : CONSOLE_PATHS) {
                assertThat((Object) statusOf(client, path))
                        .as("GET %s with bootui.enabled=OFF", path)
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }
        });
    }

    /**
     * The gate is the bean condition, not the filter: with {@code prod} active none of the console beans is
     * ever created, so there is no controller to reach and no engine service holding live application state.
     */
    @Test
    void createsNoGatedConsoleBeanInTheProdEnvironment() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(), "prod")) {
            for (Class<?> type : GATED_BEANS) {
                assertThat(context.findBean(type))
                        .as("bean %s with the prod environment active", type.getSimpleName())
                        .isEmpty();
            }
        }
    }

    /**
     * The same, stated exhaustively rather than by sample: apart from the always-registered shell guard,
     * no BootUI bean definition may resolve its condition to {@code true} in a production run. This is the
     * assertion that keeps a newly added console bean from shipping without {@code @RequiresBootUi}.
     */
    @Test
    void leavesOnlyTheAlwaysRegisteredShellGuardEnabledInTheProdEnvironment() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(), "prod")) {
            List<String> enabledBootUiBeans = context.getAllBeanDefinitions().stream()
                    .filter(definition -> definition.isEnabled(context))
                    .map(definition -> definition.getBeanType().getName())
                    .filter(name -> name.startsWith("io.github.jdubois.bootui."))
                    .distinct()
                    .sorted()
                    .toList();

            assertThat(enabledBootUiBeans).containsExactly(BootUiProdShellGuardFilter.class.getName());
        }
    }

    /** The guard is the one ungated bean — it must be present precisely because everything else is not. */
    @Test
    void keepsTheAlwaysRegisteredShellGuardInTheProdEnvironment() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(), "prod")) {
            assertThat(context.findBean(BootUiProdShellGuardFilter.class)).isPresent();
        }
    }

    /**
     * The 404 is BootUI's own, not an incidental routing miss: the shell guard renders it with the shared
     * {@link BootUiSecurityHeaders} policy, so a dark console still refuses to be framed or cached.
     */
    @Test
    void rendersTheDarkConsole404WithTheSharedSecurityHeaderPolicy() {
        withServer(Map.of(), (client) -> {
            var shell = responseOf(client, "/bootui");
            assertThat(shell.getHeaders().get(BootUiSecurityHeaders.CACHE_CONTROL))
                    .isEqualTo(BootUiSecurityHeaders.NO_CACHE);
            assertThat(shell.getHeaders().get(BootUiSecurityHeaders.CONTENT_SECURITY_POLICY))
                    .isEqualTo(BootUiSecurityHeaders.CSP_VALUE);
            assertThat(shell.getHeaders().get(BootUiSecurityHeaders.X_FRAME_OPTIONS))
                    .isEqualTo(BootUiSecurityHeaders.DENY);

            assertThat(responseOf(client, "/bootui/api/panels").getHeaders().get(BootUiSecurityHeaders.CACHE_CONTROL))
                    .isEqualTo(BootUiSecurityHeaders.NO_STORE);
        });
    }

    /**
     * {@code bootui.enabled=ON} is the documented escape hatch: it overrides a disabled environment and
     * lights the console up, so an operator who deliberately opts in gets a working console (and a warning,
     * pinned by {@link BootUiMicronautActivationResolverTest}).
     */
    @Test
    void servesTheConsoleWhenProdIsForcedOn() {
        withServer(Map.of(BootUiMicronautActivationResolver.ENABLED_KEY, "ON"), (client) -> {
            assertThat((Object) statusOf(client, "/bootui")).isEqualTo(HttpStatus.OK);
            assertThat((Object) statusOf(client, "/bootui/api/panels")).isEqualTo(HttpStatus.OK);
            assertThat((Object) statusOf(client, "/bootui/api/overview")).isEqualTo(HttpStatus.OK);
        });
    }

    @Test
    void createsTheGatedConsoleBeansWhenProdIsForcedOn() {
        try (ApplicationContext context =
                ApplicationContext.run(Map.of(BootUiMicronautActivationResolver.ENABLED_KEY, "ON"), "prod")) {
            for (Class<?> type : GATED_BEANS) {
                assertThat(context.findBean(type))
                        .as("bean %s with bootui.enabled=ON", type.getSimpleName())
                        .isPresent();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private static void withServer(Map<String, Object> properties, Consumer<HttpClient> assertions) {
        withServer(properties, List.of("prod"), assertions);
    }

    private static void withServer(
            Map<String, Object> properties, List<String> environments, Consumer<HttpClient> assertions) {
        Map<String, Object> effective = new java.util.LinkedHashMap<>(properties);
        effective.put("micronaut.server.port", -1);
        try (EmbeddedServer server =
                        ApplicationContext.run(EmbeddedServer.class, effective, environments.toArray(String[]::new));
                HttpClient client = HttpClient.create(server.getURL())) {
            assertions.accept(client);
        }
    }

    private static HttpStatus statusOf(HttpClient client, String path) {
        return responseOf(client, path).getStatus();
    }

    /** Returns the response for {@code path}, unwrapping the exception Micronaut raises for a 4xx. */
    private static HttpResponse<?> responseOf(HttpClient client, String path) {
        try {
            return client.toBlocking().exchange(HttpRequest.GET(path), String.class);
        } catch (HttpClientResponseException failure) {
            return failure.getResponse();
        }
    }
}
