package io.github.jdubois.bootui.micronaut;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Boots a real Micronaut server with the BootUI adapter on the classpath and exercises the console
 * end-to-end: the DI wiring, the filters and the controllers together.
 *
 * <p>Micronaut deduces the {@code test} environment when running under JUnit, which is one of BootUI's
 * default enabled environments, so the console is active here exactly as it is in development.
 *
 * <p>The JSON stack under these assertions is <strong>micronaut-serde-jackson</strong> (see this module's
 * POM), the default for a new Micronaut 4 application and the stricter of the two: Serde refuses any type
 * with no compile-time introspection. Walking every live panel therefore doubles as the end-to-end proof
 * that {@code BootUiSerdeImports} covers the whole API surface. The same assertions run against
 * micronaut-jackson-databind in {@code bootui-micronaut-sample-app}, since the two stacks cannot share a
 * classpath.
 */
@MicronautTest
class BootUiMicronautSmokeTest {

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

    @Test
    void servesTheOverviewChrome() {
        Map<?, ?> overview = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/overview"), Map.class);

        assertThat(overview.get("frameworkName")).isEqualTo("Micronaut");
        assertThat(overview.get("bootUiVersion")).isNotNull();
    }

    @Test
    void servesTheShellWithTheInjectedBrowserBase() {
        String html = client.toBlocking().retrieve(HttpRequest.GET("/bootui"), String.class);

        assertThat(html).contains("<base href=\"/bootui/\"");
        assertThat(html).contains("name=\"bootui-api-path\"");
    }

    @Test
    void answersTheLivePanelsThisAdapterWiresUp() {
        for (String path : new String[] {
            "/bootui/api/beans",
            "/bootui/api/mappings",
            "/bootui/api/config",
            "/bootui/api/loggers",
            "/bootui/api/threads",
            "/bootui/api/memory",
            "/bootui/api/live-memory",
            "/bootui/api/jvm-tuning",
            "/bootui/api/metrics",
            "/bootui/api/health",
            "/bootui/api/heap-dump",
            "/bootui/api/architecture",
            "/bootui/api/http-exchanges",
            "/bootui/api/exceptions",
            "/bootui/api/log-tail/recent",
            "/bootui/api/security-logs",
            "/bootui/api/profile-diff",
            "/bootui/api/rest-api",
            "/bootui/api/rest-api/error-contract",
            "/bootui/api/pentesting",
            "/bootui/api/scheduled",
            "/bootui/api/cache",
            "/bootui/api/flyway/migrations",
            "/bootui/api/liquibase/changesets",
            "/bootui/api/database-connection-pools/pools",
            "/bootui/api/sql-trace",
            "/bootui/api/database-advisor",
            "/bootui/api/hibernate",
            "/bootui/api/hibernate-statistics",
            "/bootui/api/vulnerabilities",
            "/bootui/api/traces",
            "/bootui/api/ai/overview",
            "/bootui/api/copilot/dashboard",
            "/bootui/api/claude-code/dashboard",
            "/bootui/api/websockets",
            "/bootui/api/fault-tolerance",
            "/bootui/api/rest-client-trace",
            "/bootui/api/email",
            "/bootui/api/github",
            "/bootui/api/activity",
            "/bootui/api/mcp-server",
            "/bootui/api/cli",
            "/bootui/api/activity/service-map"
        }) {
            var response = client.toBlocking().exchange(HttpRequest.GET(path), Object.class);
            assertThat((Object) response.getStatus()).as("GET %s", path).isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * A write endpoint, which exercises the JSON stack in the other direction: the request body has to be
     * <em>de</em>serialized into a record the controller declares for itself, and the answer serialized back.
     * Under Serde both halves need the introspections {@code BootUiSerdeImports} declares.
     */
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

    /**
     * The MCP transport writes its JSON-RPC envelope itself rather than handing a Jackson tree to the
     * server's JSON stack — a Jackson {@code JsonNode} is unwritable under Serde. The server is off by
     * default, so the expected answer is the canonical "disabled" error at HTTP 200, which is exactly the
     * response shape this asserts.
     */
    @Test
    void answersTheMcpTransportWithAJsonRpcEnvelope() {
        Map<?, ?> response = client.toBlocking()
                .retrieve(
                        HttpRequest.POST("/bootui/api/mcp", Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
                                .contentType(io.micronaut.http.MediaType.APPLICATION_JSON_TYPE),
                        Map.class);

        assertThat(response.get("jsonrpc")).isEqualTo("2.0");
        assertThat(response.get("id")).isEqualTo(1);
        assertThat(response.get("error")).isNotNull();
    }

    /**
     * The safety guard must reject a cross-site write before routing, so an unmatched path under the
     * console is rejected rather than answered with a bare 404 that skipped the guard.
     */
    @Test
    void rejectsCrossSiteWrites() {
        HttpClientResponseException failure = org.junit.jupiter.api.Assertions.assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(HttpRequest.POST("/bootui/api/does-not-exist", "{}")
                                .header("Origin", "http://evil.example")));

        assertThat((Object) failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
