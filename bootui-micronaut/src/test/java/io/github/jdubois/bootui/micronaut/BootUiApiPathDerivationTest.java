package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pins the one-key move: setting {@code bootui.path} alone must relocate the whole console, API included.
 *
 * <p>A Micronaut {@code @Controller} placeholder default cannot reference another property, so the API
 * controllers would otherwise stay behind at {@code /bootui/api} while the shell moved.
 * {@link BootUiApiPathConfigurer} contributes the derived mount before the routes are built; these tests
 * boot a real server so they pin what the router actually registered, not just the composed path.
 */
class BootUiApiPathDerivationTest {

    @Test
    void movesTheApiMountWithTheUiMount() {
        withServer(Map.of("bootui.path", "/console"), client -> {
            assertThat(client.exchange(HttpRequest.GET("/console/api/panels"))
                            .status()
                            .getCode())
                    .isEqualTo(HttpStatus.OK.getCode());
            assertThatThrownBy(() -> client.exchange(HttpRequest.GET("/bootui/api/panels")))
                    .isInstanceOf(HttpClientResponseException.class)
                    .extracting(exception -> ((HttpClientResponseException) exception)
                            .getStatus()
                            .getCode())
                    .isEqualTo(HttpStatus.NOT_FOUND.getCode());
        });
    }

    @Test
    void composesWithTheServerContextPath() {
        withServer(Map.of("bootui.path", "/console", "micronaut.server.context-path", "/app"), client -> {
            assertThat(client.exchange(HttpRequest.GET("/app/console/api/panels"))
                            .status()
                            .getCode())
                    .isEqualTo(HttpStatus.OK.getCode());
        });
    }

    @Test
    void leavesAnExplicitApiMountAlone() {
        try (ApplicationContext context =
                ApplicationContext.run(Map.of("bootui.path", "/console", "bootui.api-path", "/console/rest"), "test")) {
            assertThat(context.getEnvironment().getProperty(MicronautBootUiPaths.API_PATH_KEY, String.class))
                    .hasValue("/console/rest");
        }
    }

    /** The path composition every filter and the injected browser base use must agree with the routes. */
    @Test
    void agreesWithTheComposedApiPath() {
        try (ApplicationContext context = ApplicationContext.run(Map.of("bootui.path", "/console"), "test")) {
            assertThat(MicronautBootUiPaths.apiPath(context.getEnvironment())).isEqualTo("/console/api");
            assertThat(context.getEnvironment().getProperty(MicronautBootUiPaths.API_PATH_KEY, String.class))
                    .hasValue("/console/api");
        }
    }

    /**
     * Boots a real server on a random port. {@code micronaut-security} is on this module's test classpath
     * (it is a {@code provided} dependency compiled against by the Security Logs binding), and would answer
     * an unmatched route with 401 rather than 404; it is switched off here so these assertions are about
     * routing only.
     */
    private static void withServer(Map<String, Object> properties, Consumer<BlockingHttpClient> assertions) {
        Map<String, Object> configuration = new LinkedHashMap<>(properties);
        configuration.put("micronaut.security.enabled", false);
        try (EmbeddedServer server = ApplicationContext.run(EmbeddedServer.class, configuration, "test");
                HttpClient client = server.getApplicationContext().createBean(HttpClient.class, server.getURL())) {
            assertions.accept(client.toBlocking());
        }
    }
}
