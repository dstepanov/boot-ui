package io.github.jdubois.bootui.micronautsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.core.dto.MappingDto;
import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.core.dto.MetricsReport;
import io.github.jdubois.bootui.core.dto.PanelsReport;
import io.github.jdubois.bootui.core.dto.WebSocketEndpointDto;
import io.github.jdubois.bootui.core.dto.WebSocketReport;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The sample application's own end-to-end test: it boots the app exactly as {@code exec:java} does and
 * checks both halves of what the sample exists to demonstrate — that the application's endpoints work, and
 * that the console mounted alongside them describes <em>this</em> application rather than an empty shell.
 *
 * <p>Every console assertion below is about real, application-specific data ({@link CatalogController}'s
 * routes, {@link FlakyService}'s policies, {@link EchoWebSocket}'s endpoint, live health indicators and
 * Micrometer meters), so the test fails if a provider silently degrades to "unavailable" — the failure
 * mode a status-code-only smoke test cannot see.
 *
 * <p>Micronaut deduces the {@code test} environment under JUnit, which is one of BootUI's default enabled
 * environments, so the console is live here exactly as it is under the sample's default {@code dev}.
 */
@MicronautTest
class BootUiMicronautSampleAppTest {

    @Inject
    @Client("/")
    HttpClient client;

    // -----------------------------------------------------------------------
    // The sample application itself
    // -----------------------------------------------------------------------

    @Test
    void servesTheApplicationsOwnCatalogEndpoints() {
        List<String> titles = client.toBlocking().retrieve(HttpRequest.GET("/catalog"), Argument.listOf(String.class));

        assertThat(titles).contains("Refactoring");
        assertThat(client.toBlocking().retrieve(HttpRequest.GET("/catalog/0"), String.class))
                .isEqualTo("The Pragmatic Programmer");
    }

    /** The retrying endpoint must actually succeed, so the Fault Tolerance panel has real events to record. */
    @Test
    void servesTheRetryingCatalogEndpoint() {
        assertThat(client.toBlocking().retrieve(HttpRequest.GET("/catalog/flaky"), String.class))
                .startsWith("succeeded after ");
    }

    // -----------------------------------------------------------------------
    // The console, describing this application
    // -----------------------------------------------------------------------

    @Test
    void reportsTheMicronautPlatformInThePanelManifest() {
        PanelsReport manifest = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/panels"), PanelsReport.class);

        assertThat(manifest.platform()).isEqualTo(PanelsReport.PLATFORM_MICRONAUT);
        assertThat(manifest.panels()).isNotEmpty();
    }

    @Test
    void listsTheApplicationsOwnRoutesInTheMappingsPanel() {
        MappingsReport mappings =
                client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/mappings"), MappingsReport.class);

        assertThat(mappings.mappings())
                .extracting(MappingDto::pattern)
                .contains("/catalog", "/catalog/{index}", "/catalog/flaky");
        assertThat(mappings.mappings())
                .filteredOn(mapping -> "/catalog/flaky".equals(mapping.pattern()) && "GET".equals(mapping.method()))
                .singleElement()
                .satisfies(mapping -> assertThat(mapping.handler()).contains(CatalogController.class.getSimpleName()));
    }

    @Test
    void listsTheApplicationsRetryAndCircuitBreakerPoliciesInTheFaultTolerancePanel() {
        FaultToleranceReport report = client.toBlocking()
                .retrieve(HttpRequest.GET("/bootui/api/fault-tolerance"), FaultToleranceReport.class);

        assertThat(report.faultTolerancePresent()).isTrue();
        assertThat(report.providers()).isNotEmpty();
        assertThat(report.policies())
                .extracting(policy -> policy.target() + "#" + policy.type())
                .anyMatch(entry -> entry.contains(FlakyService.class.getSimpleName()) && entry.endsWith("RETRY"))
                .anyMatch(entry ->
                        entry.contains(FlakyService.class.getSimpleName()) && entry.endsWith("CIRCUIT_BREAKER"));
    }

    @Test
    void listsTheApplicationsWebSocketEndpointInTheWebSocketsPanel() {
        WebSocketReport report =
                client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/websockets"), WebSocketReport.class);

        assertThat(report.available()).isTrue();
        assertThat(report.endpoints()).extracting(WebSocketEndpointDto::path).contains("/echo");
        assertThat(report.endpoints())
                .filteredOn(endpoint -> "/echo".equals(endpoint.path()))
                .singleElement()
                .satisfies(endpoint -> assertThat(endpoint.handlerClass()).contains(EchoWebSocket.class.getName()));
    }

    /** The sample adds {@code micronaut-management}, so Health must report real indicators, not "unavailable". */
    @Test
    void reportsRealHealthIndicatorsBecauseTheSampleAddsManagement() {
        HealthNodeDto health = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/health"), HealthNodeDto.class);

        assertThat(health.available()).isTrue();
        assertThat(health.unavailableReason()).isNull();
        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.components()).isNotEmpty();
    }

    /** The sample adds {@code micronaut-micrometer}, so Metrics must report real meters, not "unavailable". */
    @Test
    void reportsRealMetersBecauseTheSampleAddsMicrometer() {
        MetricsReport metrics =
                client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/metrics"), MetricsReport.class);

        assertThat(metrics.metricsAvailable()).isTrue();
        assertThat(metrics.total()).isPositive();
        assertThat(metrics.meters()).isNotEmpty();
    }

    /** The console describes the sample, so its Overview must name this application and the Micronaut runtime. */
    @Test
    void namesTheSampleApplicationInTheOverview() {
        var overview = client.toBlocking()
                .retrieve(HttpRequest.GET("/bootui/api/overview"), Argument.mapOf(String.class, Object.class));

        assertThat(overview.get("applicationName")).isEqualTo("bootui-micronaut-sample-app");
        assertThat(overview.get("frameworkName")).isEqualTo("Micronaut");
    }
}
