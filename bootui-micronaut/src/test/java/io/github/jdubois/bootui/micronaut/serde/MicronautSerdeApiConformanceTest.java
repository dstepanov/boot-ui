package io.github.jdubois.bootui.micronaut.serde;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Runs the shared, framework-neutral API contract against the adapter on {@code micronaut-serde-jackson}.
 *
 * <p>{@code MicronautApiConformanceTest} in {@code bootui-micronaut-sample-app} runs the identical suite on
 * {@code micronaut-jackson-databind}. Together they are what makes "the adapter works on both Micronaut JSON
 * stacks" a checked claim rather than a hope: the two stacks share no serialization code at all — one
 * reflects at runtime, the other writes compile-time introspections — so every wire-shape guarantee the
 * suite asserts has to be earned twice. A smoke test can only say the endpoints answer; this says they
 * answer the same thing.
 *
 * <p>It also gives {@link BootUiSerdeImports} the coverage it most needs. Serde fails at request time, not
 * at build time, and a missing introspection or a lost {@code @SerdeImport} mix-in shows up only as a 500 or
 * a quietly-dropped field on the one endpoint that returns the affected type — so walking the entire
 * contract on Serde is the strongest available check that the import list is both complete and correctly
 * annotated.
 *
 * <p>This module has no sample application, so {@code @MicronautTest} boots the adapter's own beans on a
 * random port. That is a deliberately barer application than the sample: fewer panels find live data.
 * Nothing in the suite depends on that — panel availability is computed by {@code MicronautPanelAvailability}
 * rather than encoded in the expected-panels fixture, and safe-GET coverage skips panels the live manifest
 * reports as unavailable — so both runners share one fixture.
 *
 * <p>The properties mirror the sample runner's and exist purely to arm assertions in the shared suite:
 * {@code bootui.panels.copilot.enabled=false} arms the disabled-panel rejection,
 * {@code bootui.panels.heap-dump.read-only=true} arms the read-only rejection, and
 * {@code bootui.conformance.api-token} is the fixture the secret-masking assertion looks for.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "bootui.panels.copilot.enabled", value = "false")
@Property(name = "bootui.panels.heap-dump.read-only", value = "true")
@Property(name = "bootui.heap-dump.capture-enabled", value = "false")
@Property(name = "bootui.claude-code.enabled", value = "OFF")
@Property(name = "bootui.conformance.api-token", value = "conformance-raw-secret-value")
class MicronautSerdeApiConformanceTest extends AbstractBootUiApiConformanceTest {

    @Inject
    EmbeddedServer server;

    @Override
    protected String baseUrl() {
        return server.getURL().toExternalForm();
    }

    @Override
    protected String expectedPanelsResource() {
        return "/io/github/jdubois/bootui/conformance/expected-panels-micronaut.json";
    }

    @Override
    protected Runtime runtime() {
        return Runtime.MICRONAUT;
    }

    /**
     * The Configuration panel is action-capable in the shared registry, but Micronaut has no runtime
     * property-override write path (MN-9), so the adapter reports the panel inherently read-only and
     * exposes no write route — exactly as the Quarkus adapter does.
     */
    @Override
    protected Set<String> actionlessPanels() {
        return Set.of("config");
    }
}
