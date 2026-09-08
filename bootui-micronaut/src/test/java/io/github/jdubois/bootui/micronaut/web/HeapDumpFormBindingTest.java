package io.github.jdubois.bootui.micronaut.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Exercises the adapter's only form-encoded route, {@code POST /bootui/api/heap-dump/delete}.
 *
 * <p>The shared UI really does send {@code application/x-www-form-urlencoded} here, but every conformance
 * runner sets {@code bootui.panels.heap-dump.read-only=true} to arm the read-only rejection assertion — so
 * what the suite covers is the refusal, and the binding behind it never runs. This test switches that panel
 * back on, which is the only way the {@code @Body Map<String, String>} binding is reached at all.
 *
 * <p>It asks for a dump that does not exist, so nothing is written or deleted: the engine answers its
 * "Unknown heap dump" report, and that answer is itself the assertion — the name could only have reached the
 * service through the form binding. A binding failure would surface as a {@code 400} or a {@code 415}
 * instead, which is exactly the shape of the two bugs (MN-1, MN-2) that shipped from a path working on one
 * JSON stack and failing on the other. This module runs on {@code micronaut-serde-jackson};
 * {@code bootui-micronaut-sample-app} runs the same assertions on {@code micronaut-jackson-databind}.
 */
class HeapDumpFormBindingTest {

    @Test
    void bindsTheFormEncodedDeleteBodyAndReachesTheEngine() {
        try (EmbeddedServer server = ApplicationContext.run(
                        EmbeddedServer.class,
                        Map.of("micronaut.server.port", -1, "bootui.heap-dump.capture-enabled", false),
                        "test");
                HttpClient client = HttpClient.create(server.getURL())) {

            HttpResponse<HeapDumpReport> response = exchange(
                    client,
                    HttpRequest.POST("/bootui/api/heap-dump/delete", "name=conformance-missing.hprof")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED));

            assertThat((Object) response.getStatus())
                    .as("a form-encoded body must bind; a 400 or 415 here means it did not")
                    .isEqualTo(HttpStatus.OK);
            assertThat(response.body())
                    .as("the report must come back deserialized under this module's JSON stack")
                    .isNotNull();
            assertThat(response.body().capture().message())
                    .as("the name reached the engine, which is the only way this message is produced")
                    .isEqualTo("Unknown heap dump");
        }
    }

    /** A missing body must be handled as "no name given", not as a binding failure. */
    @Test
    void treatsAnAbsentFormBodyAsAnUnknownDump() {
        try (EmbeddedServer server = ApplicationContext.run(
                        EmbeddedServer.class,
                        Map.of("micronaut.server.port", -1, "bootui.heap-dump.capture-enabled", false),
                        "test");
                HttpClient client = HttpClient.create(server.getURL())) {

            HttpResponse<HeapDumpReport> response = exchange(
                    client,
                    HttpRequest.POST("/bootui/api/heap-dump/delete", "")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED));

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
            assertThat(response.body().capture().message()).isEqualTo("Unknown heap dump");
        }
    }

    private static HttpResponse<HeapDumpReport> exchange(HttpClient client, HttpRequest<?> request) {
        try {
            return client.toBlocking().exchange(request, HeapDumpReport.class);
        } catch (HttpClientResponseException ex) {
            throw new AssertionError(
                    "form-encoded delete was rejected with " + ex.getStatus() + ": "
                            + ex.getResponse().getBody(String.class).orElse(""),
                    ex);
        }
    }
}
