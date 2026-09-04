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
