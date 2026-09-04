package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.McpServerStatus;
import io.github.jdubois.bootui.core.dto.McpToolInfo;
import io.github.jdubois.bootui.engine.mcp.McpDispatcher;
import io.github.jdubois.bootui.engine.mcp.McpProtocol;
import io.github.jdubois.bootui.engine.mcp.McpRuntimeStats;
import io.github.jdubois.bootui.engine.mcp.McpTool;
import io.github.jdubois.bootui.micronaut.MicronautBootUiPaths;
import io.github.jdubois.bootui.micronaut.MicronautPanelAccessConfig;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.mcp.McpServerState;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import java.util.List;

/**
 * Controller for the MCP Server panel ({@code GET /bootui/api/mcp-server} and its toggle).
 *
 * <p>Reports what the MCP server is currently doing — whether it is on, whether that overrides
 * configuration, which tools it advertises, and its runtime counters — and lets the developer switch it on
 * or off without restarting. Each advertised tool carries the live enable/read-only state of its backing
 * panel, so the panel shows exactly what an agent would be allowed to run.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/mcp-server")
public class McpServerController {

    private final McpServerState state;
    private final McpDispatcher dispatcher;
    private final MicronautPanelAccessConfig accessConfig;
    private final int maxResults;
    private final String mcpEndpoint;

    public McpServerController(McpServerState state, McpDispatcher dispatcher, Environment environment) {
        this.state = state;
        this.dispatcher = dispatcher;
        this.accessConfig = new MicronautPanelAccessConfig(environment);
        this.maxResults = Math.max(
                1,
                environment.getProperty("bootui.mcp.max-results", Integer.class).orElse(200));
        this.mcpEndpoint = MicronautBootUiPaths.applicationApiPath(environment) + "/mcp";
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public McpServerStatus status() {
        return buildStatus();
    }

    @Post("/toggle")
    @Produces(MediaType.APPLICATION_JSON)
    public McpServerStatus toggle(@Body @Nullable ToggleRequest request) {
        boolean target = request == null || request.enabled() == null ? !state.isEnabled() : request.enabled();
        state.setEnabled(target);
        return buildStatus();
    }

    private McpServerStatus buildStatus() {
        List<McpToolInfo> toolInfos =
                dispatcher.tools().stream().map(this::toInfo).toList();
        McpRuntimeStats.Snapshot stats = dispatcher.runtimeStats().snapshot();
        return new McpServerStatus(
                state.isEnabled(),
                state.configuredMode(),
                state.overridden(),
                McpProtocol.SERVER_NAME,
                serverVersion(),
                "http",
                mcpEndpoint,
                McpProtocol.DEFAULT_PROTOCOL_VERSION,
                maxResults,
                stats.callCount(),
                stats.totalLatencyMillis(),
                stats.capacityRefusals(),
                stats.timeouts(),
                stats.responseLimitRefusals(),
                toolInfos.size(),
                toolInfos);
    }

    private McpToolInfo toInfo(McpTool tool) {
        return new McpToolInfo(
                tool.name(),
                tool.description(),
                tool.panelId(),
                tool.action(),
                accessConfig.isPanelEnabled(tool.panelId()),
                accessConfig.isPanelReadOnly(tool.panelId()));
    }

    private static String serverVersion() {
        String version = McpServerController.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    /** The toggle request body; a {@code null} value flips the current state. */
    public record ToggleRequest(Boolean enabled) {}
}
