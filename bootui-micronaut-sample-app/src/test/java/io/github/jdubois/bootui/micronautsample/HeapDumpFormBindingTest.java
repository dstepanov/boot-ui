package io.github.jdubois.bootui.micronautsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.core.dto.HeapDumpReport;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The {@code micronaut-jackson-databind} half of the adapter's form-encoded route coverage.
 *
 * <p>{@code POST /bootui/api/heap-dump/delete} is the only route in the adapter that takes an
 * {@code application/x-www-form-urlencoded} body, and every conformance runner switches the Heap Dump panel
 * to read-only to arm the read-only rejection assertion — so the binding itself is never reached there. The
 * twin of this test in {@code bootui-micronaut} covers {@code micronaut-serde-jackson}; running both is what
 * makes this a two-stack promise rather than a one-stack one, which is the exact shape of the two bugs
 * (MN-1, MN-2) that shipped from a path that worked on one JSON stack and 500'd on the other.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "bootui.heap-dump.capture-enabled", value = "false")
class HeapDumpFormBindingTest {

    @Inject
    EmbeddedServer server;

    @Test
    void bindsTheFormEncodedDeleteBodyAndReachesTheEngine() {
        try (HttpClient client = HttpClient.create(server.getURL())) {
            HttpResponse<HeapDumpReport> response;
            try {
                response = client.toBlocking()
                        .exchange(
                                HttpRequest.POST("/bootui/api/heap-dump/delete", "name=conformance-missing.hprof")
                                        .contentType(MediaType.APPLICATION_FORM_URLENCODED),
                                HeapDumpReport.class);
            } catch (HttpClientResponseException ex) {
                throw new AssertionError("form-encoded delete was rejected with " + ex.getStatus(), ex);
            }

            assertThat((Object) response.getStatus()).isEqualTo(HttpStatus.OK);
            assertThat(response.body()).isNotNull();
            assertThat(response.body().capture().message()).isEqualTo("Unknown heap dump");
        }
    }
}
