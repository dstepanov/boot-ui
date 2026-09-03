package io.github.jdubois.bootui.autoconfigure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.autoconfigure.reactive.ReactiveBootUiMcpTools;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog.Stack;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins each Spring registry to {@link McpToolCatalog}.
 *
 * <p>The catalog is what the {@code bootui} CLI command tree and the {@code /bootui/api/cli} facade are
 * generated from, so a tool that exists in a registry but not in the catalog — or vice versa — would silently
 * be unreachable from the CLI. These tests make that condition a build failure instead.
 */
class McpToolCatalogParityTests {

    @Test
    void springMvcRegistryAdvertisesExactlyTheCatalog() {
        assertThat(names(springMvcTools()))
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.namesFor(Stack.SPRING_MVC));
    }

    @Test
    void reactiveRegistryAdvertisesExactlyTheCatalog() {
        assertThat(names(reactiveTools()))
                .containsExactlyInAnyOrderElementsOf(McpToolCatalog.namesFor(Stack.SPRING_WEBFLUX));
    }

    @Test
    void springMvcRegistryMatchesTheCatalogSchemaPanelAndActionKind() {
        assertCatalogShape(springMvcTools(), Stack.SPRING_MVC);
    }

    @Test
    void reactiveRegistryMatchesTheCatalogSchemaPanelAndActionKind() {
        assertCatalogShape(reactiveTools(), Stack.SPRING_WEBFLUX);
    }

    private static void assertCatalogShape(List<McpTool> tools, Stack stack) {
        assertThat(tools).isNotEmpty();
        assertThat(tools).allSatisfy(tool -> {
            McpToolCatalog.Entry entry = McpToolCatalog.require(tool.name(), stack);
            assertThat(tool.schema()).as("%s schema", tool.name()).isEqualTo(entry.schema());
            assertThat(tool.panelId()).as("%s panel", tool.name()).isEqualTo(entry.panelId());
            assertThat(tool.action()).as("%s action", tool.name()).isEqualTo(entry.action());
        });
    }

    private static List<McpTool> springMvcTools() {
        return McpToolsRegistryFixture.maximalRegistry(BootUiMcpTools.class, "tools");
    }

    private static List<McpTool> reactiveTools() {
        return McpToolsRegistryFixture.maximalRegistry(ReactiveBootUiMcpTools.class, "tools");
    }

    private static List<String> names(List<McpTool> tools) {
        return tools.stream().map(McpTool::name).toList();
    }
}
