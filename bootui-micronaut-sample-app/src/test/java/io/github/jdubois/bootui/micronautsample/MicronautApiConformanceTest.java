package io.github.jdubois.bootui.micronautsample;

import io.github.jdubois.bootui.conformance.AbstractBootUiApiConformanceTest;
import io.github.jdubois.bootui.conformance.BootUiApiContractCatalog.Runtime;
import io.micronaut.context.annotation.Property;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * Runs the shared, framework-neutral {@link AbstractBootUiApiConformanceTest} contract against the
 * Micronaut adapter, by booting this sample application on a random port under {@code @MicronautTest}.
 *
 * <p>This is the Micronaut mirror of the Spring sample's {@code SpringApiConformanceTest} and the Quarkus
 * integration tests' {@code BootUiQuarkusApiConformanceTest}: all three adapters answer the exact same
 * {@code /bootui/api/**} contract, so the shared Vue UI binds to one stable shape whichever stack is
 * underneath. JUnit makes Micronaut deduce the {@code test} environment, which is one of BootUI's default
 * enabled environments, so the console is live here without any extra configuration.
 *
 * <p>Two properties exist purely to arm assertions in the shared suite:
 * {@code bootui.panels.copilot.enabled=false} arms
 * {@link AbstractBootUiApiConformanceTest#panelDisabledRequestIsRejectedWithCanonicalBody} and
 * {@code bootui.panels.heap-dump.read-only=true} arms
 * {@link AbstractBootUiApiConformanceTest#panelReadOnlyActionIsRejectedWithCanonicalBody}; both are safe
 * because safe-GET coverage skips disabled panels and never invokes heap-dump actions.
 * {@code bootui.conformance.api-token} is the fixture the masking assertion looks for.
 */
@MicronautTest
@Property(name = "micronaut.server.port", value = "-1")
@Property(name = "bootui.panels.copilot.enabled", value = "false")
@Property(name = "bootui.panels.heap-dump.read-only", value = "true")
@Property(name = "bootui.heap-dump.capture-enabled", value = "false")
@Property(name = "bootui.claude-code.enabled", value = "OFF")
@Property(name = "bootui.conformance.api-token", value = "conformance-raw-secret-value")
class MicronautApiConformanceTest extends AbstractBootUiApiConformanceTest {

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

    @Override
    protected Set<String> expectedErrorContractComponents() {
        return Set.of("CatalogExceptionHandler");
    }
}
