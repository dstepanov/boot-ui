package io.github.jdubois.bootui.micronaut.json;

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
 * Pins the wire shape both Micronaut JSON stacks would otherwise take away — here on
 * {@code micronaut-serde-jackson}, which is what this module's tests run under.
 *
 * <p>Both stacks default to a "skip it if it is empty" inclusion policy: Micronaut's Jackson integration
 * defaults {@code jackson.serialization-inclusion} to {@code NON_EMPTY}, and Micronaut Serde defaults
 * {@code serde.serialization.inclusion} to the same thing. Either way an empty list or map property is
 * dropped from the JSON entirely, and so is a {@code null}. The shared UI and the shared conformance
 * contract both require those fields to be present — and a panel with nothing to report is precisely when
 * they are empty — so BootUI overrides the policy for its own types on both stacks:
 * {@link BootUiJsonInclusionCustomizer} installs a Jackson mix-in resolver under databind, and every
 * {@code @SerdeImport} in {@code BootUiSerdeImports} carries an always-include mix-in under Serde.
 *
 * <p>The assertions are on the <em>raw body</em> rather than a deserialized DTO, because deserializing would
 * silently fill a missing field back in with an empty default and hide the regression.
 *
 * <p>{@code BootUiJsonInclusionContractTest} in {@code bootui-micronaut-sample-app} is the twin of this
 * class and makes the same assertions on {@code micronaut-jackson-databind}. The two stacks are mutually
 * exclusive on one classpath, so the contract is pinned by two modules rather than by two profiles.
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

    /**
     * The databind mix-in resolver answers for BootUI's own classes only; every other class keeps the host's
     * inclusion policy. Asserted here rather than in the sample app because it is a pure unit check on a
     * class this module owns — plain {@code jackson-databind} is a compile dependency of the adapter, so the
     * resolver loads even though no Jackson {@code ObjectMapper} bean exists on the Serde stack.
     */
    @Test
    void theDatabindMixInAppliesOnlyToBootUiOwnedClasses() {
        BootUiJsonInclusionCustomizer.BootUiMixInResolver resolver =
                new BootUiJsonInclusionCustomizer.BootUiMixInResolver();

        assertThat(resolver.findMixInClassFor(io.github.jdubois.bootui.core.dto.PanelsReport.class))
                .isEqualTo(BootUiJsonInclusionCustomizer.AlwaysInclude.class);
        assertThat(resolver.findMixInClassFor(String.class)).isNull();
        assertThat(resolver.findMixInClassFor(Map.class)).isNull();
        assertThat(resolver.findMixInClassFor(null)).isNull();
    }
}
