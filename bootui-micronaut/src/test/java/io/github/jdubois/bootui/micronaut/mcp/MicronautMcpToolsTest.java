package io.github.jdubois.bootui.micronaut.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.engine.mcp.McpToolCatalog;
import io.github.jdubois.bootui.micronaut.MicronautPanelAvailability;
import io.micronaut.context.ApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pins the MCP tool registry against the shared catalog. An agent must only ever be offered tools this
 * stack can actually run, and each one must carry the catalog's own schema, panel and action kind — those
 * are what the dispatcher enforces panel policy and read-only refusal with.
 */
class MicronautMcpToolsTest {

    @Test
    void advertisesOnlyToolsTheCatalogAssignsToMicronaut() {
        withTools(tools -> assertThat(tools)
                .extracting(McpTool::name)
                .isSubsetOf(McpToolCatalog.namesFor(McpToolCatalog.Stack.MICRONAUT)));
    }

    @Test
    void matchesTheSharedCatalogSchemaPanelAndActionKind() {
        withTools(tools -> assertThat(tools).allSatisfy(tool -> {
            McpToolCatalog.Entry entry = McpToolCatalog.require(tool.name(), McpToolCatalog.Stack.MICRONAUT);
            assertThat(tool.schema()).as("%s schema", tool.name()).isEqualTo(entry.schema());
            assertThat(tool.panelId()).as("%s panel", tool.name()).isEqualTo(entry.panelId());
            assertThat(tool.action()).as("%s action", tool.name()).isEqualTo(entry.action());
        }));
    }

    /**
     * Only tools whose panel is available are advertised, so an agent is never offered a diagnostic that
     * would immediately fail on this application.
     */
    @Test
    void advertisesOnlyAvailablePanels() {
        withContext(context -> {
            MicronautPanelAvailability availability = context.getBean(MicronautPanelAvailability.class);
            List<McpTool> tools = context.getBean(MicronautMcpTools.class).tools();
            assertThat(tools)
                    .allSatisfy(tool -> assertThat(availability.isPanelAvailable(tool.panelId()))
                            .as("%s panel %s available", tool.name(), tool.panelId())
                            .isTrue());
        });
    }

    private static void withTools(Consumer<List<McpTool>> assertion) {
        withContext(context ->
                assertion.accept(context.getBean(MicronautMcpTools.class).tools()));
    }

    private static void withContext(Consumer<ApplicationContext> assertion) {
        try (ApplicationContext context = ApplicationContext.run(Map.<String, Object>of(), "test")) {
            assertion.accept(context);
        }
    }
}
