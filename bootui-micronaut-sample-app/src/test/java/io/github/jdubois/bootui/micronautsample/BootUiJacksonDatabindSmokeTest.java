package io.github.jdubois.bootui.micronautsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the console still serves correctly on {@code micronaut-jackson-databind}.
 *
 * <p>The adapter's own {@code BootUiMicronautSmokeTest} runs the same endpoints under
 * {@code micronaut-serde-jackson}. The two Micronaut JSON stacks are mutually exclusive — each publishes
 * its own {@code JsonMapper} and message-body handlers, so putting both on one classpath decides nothing
 * deterministically — which is why the two-stack promise is kept by two modules rather than by two
 * profiles inside one. This module is the databind half: it declares that dependency explicitly, exactly
 * as an application on the older stack would.
 *
 * <p>What is being guarded is a regression the {@code @SerdeImport} work could plausibly cause: the
 * compile-time introspections generated into the adapter's jar must stay inert here, and the MCP transport
 * — which now writes its own JSON-RPC bytes rather than handing a Jackson tree to the server — must still
 * answer the same envelope.
 */
@MicronautTest
class BootUiJacksonDatabindSmokeTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void servesThePanelManifestWithTheMicronautPlatformDiscriminator() {
        Map<?, ?> manifest = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/panels"), Map.class);

        assertThat(manifest.get("platform")).isEqualTo("micronaut");
        assertThat(manifest.get("panels"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isNotEmpty();
    }

    /**
     * The panels this sample actually lights up, plus the ones whose reports are the largest and most
     * deeply nested — those are where a serialization regression would surface first.
     */
    @Test
    void answersTheLivePanels() {
        for (String path : new String[] {
            "/bootui/api/overview",
            "/bootui/api/beans",
            "/bootui/api/mappings",
            "/bootui/api/config",
            "/bootui/api/loggers",
            "/bootui/api/threads",
            "/bootui/api/memory",
            "/bootui/api/metrics",
            "/bootui/api/health",
            "/bootui/api/architecture",
            "/bootui/api/rest-api",
            "/bootui/api/websockets",
            "/bootui/api/fault-tolerance",
            "/bootui/api/activity",
            "/bootui/api/cli"
        }) {
            var response = client.toBlocking().exchange(HttpRequest.GET(path), Object.class);
            assertThat((Object) response.getStatus()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        }
    }

    /** A write, so request-body binding is covered on this stack too. */
    @Test
    void readsAWriteRequestBodyAndAnswersWithTheUpdatedResource() {
        Map<?, ?> updated = client.toBlocking()
                .retrieve(
                        HttpRequest.POST(
                                "/bootui/api/loggers/io.github.jdubois.bootui.micronautsample",
                                Map.of("level", "DEBUG")),
                        Map.class);

        assertThat(updated.get("name")).isEqualTo("io.github.jdubois.bootui.micronautsample");
        assertThat(updated.get("configuredLevel")).isEqualTo("DEBUG");
    }

    /** The MCP transport, which serializes its envelope itself on every stack. */
    @Test
    void answersTheMcpTransportWithAJsonRpcEnvelope() {
        Map<?, ?> response = client.toBlocking()
                .retrieve(
                        HttpRequest.POST("/bootui/api/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                                .contentType(MediaType.APPLICATION_JSON_TYPE),
                        Map.class);

        assertThat(response.get("jsonrpc")).isEqualTo("2.0");
        assertThat(response.get("id")).isEqualTo(1);
        assertThat(response.get("error")).isNotNull();
    }
}
