package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiCatalog;
import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientException;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import io.github.jdubois.bootui.client.JsonValue;
import io.github.jdubois.bootui.client.JsonWriter;
import io.github.jdubois.bootui.client.ToolOutcome;
import io.github.jdubois.bootui.client.ToolResult;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything a command needs to do its work: where the application is, where to write, and how to turn an
 * answer into an exit code.
 *
 * <p>The client is created per invocation rather than held, because the global options are only fully known
 * once parsing has finished — {@code bootui beans --url …} sets them after the command has been selected.
 */
final class CliContext {

    private final GlobalOptions options = new GlobalOptions();
    private final ToolManifest manifest;
    private final Map<String, String> environment;
    private final boolean terminal;
    private final PrintWriter out;
    private final PrintWriter err;
    private final Function<BootUiClientOptions, BootUiClient> clientFactory;

    CliContext(
            ToolManifest manifest,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clientFactory) {
        this.manifest = manifest;
        this.environment = Map.copyOf(environment);
        this.terminal = terminal;
        this.out = out;
        this.err = err;
        this.clientFactory = clientFactory;
    }

    GlobalOptions options() {
        return options;
    }

    ToolManifest manifest() {
        return manifest;
    }

    PrintWriter out() {
        return out;
    }

    PrintWriter err() {
        return err;
    }

    private BootUiClient newClient() {
        return clientFactory.apply(options.toClientOptions(environment));
    }

    /** Calls one tool and reports it. */
    int invokeTool(ToolManifest.Tool tool, String query, Integer limit, String id) {
        try (BootUiClient client = newClient()) {
            ToolResult result = client.invoke(tool.name(), query, limit, id);
            if (result.successful()) {
                emit(result.payload(), result.rawBody());
                return ExitCodes.SUCCESS;
            }
            err.println(describe(tool, result.outcome(), result.errorMessage()));
            err.flush();
            return exitCodeFor(result.outcome());
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /** Prints what the target instance advertises, which is the authority over the bundled manifest. */
    int listTools() {
        try (BootUiClient client = newClient()) {
            JsonValue document = client.get(CliPaths.CLI);
            if (options.json(terminal)) {
                out.println(document.toJson());
                out.flush();
                return ExitCodes.SUCCESS;
            }
            BootUiCatalog catalog = BootUiCatalog.from(document);
            List<JsonValue> rows = new ArrayList<>();
            for (BootUiCatalog.CatalogTool tool : catalog.tools()) {
                ToolManifest.Tool known = manifest.byName(tool.name());
                Map<String, JsonValue> row = new LinkedHashMap<>();
                row.put("command", JsonValue.of(known == null ? "-" : known.command()));
                row.put("tool", JsonValue.of(tool.name()));
                row.put("panel", JsonValue.of(tool.panel()));
                row.put(
                        "arguments",
                        JsonValue.of(tool.arguments().isEmpty() ? "-" : String.join(", ", tool.arguments())));
                row.put("status", JsonValue.of(status(tool)));
                rows.add(JsonValue.object(row));
            }
            Map<String, JsonValue> summary = new LinkedHashMap<>();
            summary.put("endpoint", JsonValue.of(catalog.endpoint()));
            summary.put("serverVersion", JsonValue.of(catalog.serverVersion()));
            summary.put("enabled", JsonValue.of(catalog.enabled()));
            summary.put("maxResults", JsonValue.of(catalog.maxResults()));
            summary.put("tools", JsonValue.array(rows));
            emit(JsonValue.object(summary), null);
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /** Reads the MCP Server panel. */
    int mcpStatus() {
        try (BootUiClient client = newClient()) {
            JsonValue status = client.get(CliPaths.MCP_SERVER);
            emit(status, status.toJson());
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    /**
     * Flips the live MCP server state.
     *
     * <p>This is a panel action, so it is refused when the {@code mcp-server} panel is disabled or read-only —
     * the CLI does not get a privileged path to it.
     */
    int mcpToggle(boolean enabled) {
        try (BootUiClient client = newClient()) {
            JsonValue status = client.post(CliPaths.MCP_TOGGLE, "{\"enabled\":" + enabled + "}");
            emit(status, status.toJson());
            return ExitCodes.SUCCESS;
        } catch (BootUiClientException failure) {
            return fail(failure);
        }
    }

    private static String status(BootUiCatalog.CatalogTool tool) {
        if (!tool.panelEnabled()) {
            return "panel disabled";
        }
        if (tool.action() && tool.panelReadOnly()) {
            return "read-only";
        }
        return tool.action() ? "action" : "ready";
    }

    private void emit(JsonValue payload, String rawBody) {
        if (options.json(terminal)) {
            // Verbatim when we have it: the server's bytes are the contract-stable form, and re-emitting a
            // parsed tree would quietly normalize number formats and key order.
            out.println(rawBody == null || rawBody.isBlank() ? JsonWriter.pretty(payload) : rawBody);
        } else {
            out.println(new TextRenderer(options.color(terminal, environment)).render(payload));
        }
        out.flush();
    }

    private int fail(BootUiClientException failure) {
        err.println(failure.getMessage());
        if (options.verbose() && failure.getCause() != null) {
            failure.printStackTrace(err);
        }
        err.flush();
        return ExitCodes.ERROR;
    }

    /**
     * The message for a call BootUI would not run.
     *
     * <p>A tool missing from one stack is the failure most likely to look like a bug, so it says which stacks
     * do advertise it rather than leaving the reader to guess.
     */
    private String describe(ToolManifest.Tool tool, ToolOutcome outcome, String message) {
        switch (outcome) {
            case UNKNOWN_TOOL:
                String hint = tool.onEveryStack()
                        ? " The application may be running an older BootUI version."
                        : " Only "
                                + String.join(", ", tool.stacks())
                                        .toLowerCase(Locale.ROOT)
                                        .replace('_', ' ') + " advertise it.";
                return "This application does not expose '" + tool.name() + "'." + hint;
            case REFUSED_BY_POLICY:
                return message + " (panel '" + tool.panel() + "')";
            case ENDPOINT_DISABLED:
                return "The BootUI command-line endpoint is disabled on this application. "
                        + "Set bootui.cli.enabled=true to allow it.";
            default:
                return message;
        }
    }

    private static int exitCodeFor(ToolOutcome outcome) {
        // A refusal is a statement about how the target is configured, not a failed request, so a script can
        // tell "BootUI said no" apart from "the call did not work".
        switch (outcome) {
            case REFUSED_BY_POLICY:
            case ENDPOINT_DISABLED:
                return ExitCodes.REFUSED;
            default:
                return ExitCodes.ERROR;
        }
    }

    /** The BootUI API paths the CLI talks to that are not tool calls. */
    static final class CliPaths {

        static final String CLI = "/cli";
        static final String MCP_SERVER = "/mcp-server";
        static final String MCP_TOGGLE = "/mcp-server/toggle";

        private CliPaths() {}
    }
}
