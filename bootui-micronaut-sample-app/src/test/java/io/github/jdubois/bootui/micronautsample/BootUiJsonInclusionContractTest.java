package io.github.jdubois.bootui.micronautsample;

import static org.assertj.core.api.Assertions.assertThat;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code micronaut-jackson-databind} half of BootUI's JSON inclusion contract: every field is written,
 * including empty collections, empty maps and nulls.
 *
 * <p>This is the twin of {@code BootUiJsonInclusionContractTest} in {@code bootui-micronaut}, which makes
 * the same assertions under {@code micronaut-serde-jackson}. The contract is one contract, but the two
 * Micronaut JSON stacks are mutually exclusive on a classpath and each drops the same fields for its own
 * reason and needs its own fix — a runtime Jackson mix-in resolver
 * ({@code BootUiJsonInclusionCustomizer}) here, compile-time {@code @SerdeImport(mixin = …)} metadata
 * there. Two independent mechanisms is exactly why both need pinning: a fix to one cannot vouch for the
 * other, and the shared Vue UI and the shared conformance contract see only the wire bytes.
 *
 * <p>The assertions read the <em>raw body</em> rather than a deserialized DTO on purpose: deserializing
 * would silently fill a missing field back in with an empty default and hide the regression.
 */
@MicronautTest
class BootUiJsonInclusionContractTest {

    @Inject
    @Client("/")
    HttpClient client;

    /** Empty collections must be written as {@code []} / <code>{}</code>, not omitted. */
    @Test
    void writesEmptyCollectionsInsteadOfDroppingThem() {
        Map<String, List<String>> emptyFieldsByPath = Map.of(
                "/bootui/api/traces", List.of("\"traces\":[]"),
                "/bootui/api/sql-trace", List.of("\"entries\":[]"),
                "/bootui/api/heap-dump", List.of("\"dumps\":[]", "\"topClasses\":[]"),
                "/bootui/api/ai/overview", List.of("\"recent\":[]", "\"tokensByModel\":{}", "\"callsByModel\":{}"));

        emptyFieldsByPath.forEach((path, expectedFragments) -> {
            String body = client.toBlocking().retrieve(HttpRequest.GET(path), String.class);
            assertThat(body).as("GET %s", path).contains(expectedFragments);
        });
    }

    /** A null field is part of the same policy: the UI distinguishes "no reason" from "field absent". */
    @Test
    void writesNullFieldsInsteadOfDroppingThem() {
        String body = client.toBlocking().retrieve(HttpRequest.GET("/bootui/api/exceptions"), String.class);

        assertThat(body).contains("\"unavailableReason\":null");
    }
}
