package io.github.jdubois.bootui.webfluxsample;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Proves the Fault Tolerance panel is genuinely live on the reactive stack: the shared read path finds the
 * sample's Resilience4j registries, and events published from a {@code boundedElastic} scheduler thread
 * - not the request thread - still reach the shared recorder and the panel report.
 *
 * <p>Spring Retry is absent from this application on purpose, so this test also covers the half of the
 * Fault Tolerance backend's optional-dependency guards the servlet sample cannot: BootUI must start and
 * serve the panel with only one of the two supported Spring libraries on the classpath.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"bootui.enabled=ON", "spring.devtools.restart.enabled=false"})
class WebFluxFaultToleranceIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void reactivePolicyInventoryAndCapturedEventsAreReported() throws Exception {
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> beforeAnyCall = get(client, "/bootui/api/fault-tolerance");
        assertThat(beforeAnyCall.statusCode()).isEqualTo(200);
        assertThat(beforeAnyCall.body())
                .contains("\"faultTolerancePresent\":true")
                .contains("\"resilience4j\"")
                .contains("\"name\":\"inventory-service\"")
                .contains("\"type\":\"CIRCUIT_BREAKER\"")
                .contains("\"events\":[]");
        assertThat(beforeAnyCall.body()).doesNotContain("spring-retry");

        HttpResponse<String> exercised = get(client, "/api/sample/fault-tolerance");
        assertThat(exercised.statusCode()).isEqualTo(200);

        HttpResponse<String> report = get(client, "/bootui/api/fault-tolerance");
        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body())
                .contains("\"policyName\":\"inventory-service\"")
                .contains("\"provider\":\"resilience4j\"")
                .contains("\"outcome\":\"RETRY\"")
                .contains("\"outcome\":\"STATE_TRANSITION\"")
                .doesNotContain("inventory service unreachable");
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
