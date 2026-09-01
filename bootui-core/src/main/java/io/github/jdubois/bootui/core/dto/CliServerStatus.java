package io.github.jdubois.bootui.core.dto;

import java.util.List;

/**
 * The answer to {@code GET /bootui/api/cli}: whether the command-line endpoint is available, and the tool
 * catalog this instance exposes through it.
 *
 * <p>Answered even while the endpoint is disabled, so the CLI can report a precise reason rather than a bare
 * connection failure. When {@code enabled} is {@code false}, {@code tools} is empty.
 *
 * @param enabled whether tool invocation is currently accepted
 * @param serverName the server name, matching the MCP server's
 * @param serverVersion the BootUI version serving this endpoint
 * @param endpoint the relative path tools are invoked under
 * @param maxResults the {@code bootui.cli.max-results} cap applied to paginated read tools
 * @param toolCount the number of tools advertised
 * @param tools the advertised tools
 */
public record CliServerStatus(
        boolean enabled,
        String serverName,
        String serverVersion,
        String endpoint,
        int maxResults,
        int toolCount,
        List<CliToolInfo> tools) {

    public CliServerStatus {
        tools = DtoCollections.immutableCopy(tools);
    }
}
