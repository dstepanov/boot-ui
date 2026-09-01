package io.github.jdubois.bootui.engine.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class McpToolDescriptionsTests {

    @Test
    void everySpringToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.SPRING_MVC), McpToolDescriptions::spring);
    }

    @Test
    void everyReactiveToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.SPRING_WEBFLUX), McpToolDescriptions::spring);
    }

    @Test
    void everyQuarkusToolHasAgentOrientedGuidance() {
        assertDescriptions(McpToolCatalog.namesFor(McpToolCatalog.Stack.QUARKUS), McpToolDescriptions::quarkus);
    }

    private static void assertDescriptions(Set<String> names, Function<String, String> descriptionProvider) {
        assertThat(names).isNotEmpty();
        assertThat(names)
                .allSatisfy(name -> assertThat(descriptionProvider.apply(name))
                        .as(name)
                        .hasSizeGreaterThan(60)
                        .endsWith("."));
    }
}
