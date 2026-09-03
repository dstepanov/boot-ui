package io.github.jdubois.bootui.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the projection actually holds at runtime.
 *
 * <p>The manifest tests check the data; this one runs every single command and asserts it reaches the tool it
 * claims to. That is what catches a command tree that builds fine but shadows a leaf, mis-nests a group, or
 * declares an argument the tool does not take.
 */
class CommandTreeTests {

    private HttpServer server;
    private final List<String> paths = new ArrayList<>();
    private final List<String> bodies = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void everyCommandInTheManifestInvokesTheToolItProjects() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            paths.clear();
            List<String> args = new ArrayList<>(tool.path());
            if (tool.takesId()) {
                args.add("some-id");
            }
            int exitCode = run(args);
            String expected = "/bootui/api/cli/tools/" + tool.name();
            if (exitCode != ExitCodes.SUCCESS || paths.size() != 1 || !expected.equals(paths.get(0))) {
                failures.put(tool.command(), "exit " + exitCode + ", called " + paths);
            }
        }

        assertThat(failures).as("commands that did not reach their tool").isEmpty();
    }

    @Test
    void everyCommandAcceptsExactlyTheArgumentsItsSchemaDeclares() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (ToolManifest.Tool tool : ToolManifest.bundled().tools()) {
            check(failures, tool, "--query", tool.takesQuery());
            check(failures, tool, "--limit", tool.takesLimit());
        }

        assertThat(failures)
                .as("commands whose flags disagree with their MCP schema")
                .isEmpty();
    }

    @Test
    void anIdCommandRefusesToRunWithoutOne() {
        ToolManifest.Tool tool = ToolManifest.bundled().byName("get_exception_detail");

        int exitCode = run(tool.path());

        assertThat(exitCode).isEqualTo(ExitCodes.ERROR);
        assertThat(paths).as("a missing id must not become a call").isEmpty();
    }

    @Test
    void aQueryCommandSendsTheFilterItWasGiven() {
        bodies.clear();

        List<String> args =
                new ArrayList<>(ToolManifest.bundled().byName("get_beans").path());
        args.add("--query");
        args.add("dataSource");
        run(args);

        assertThat(bodies).containsExactly("{\"query\":\"dataSource\"}");
    }

    private void check(Map<String, String> failures, ToolManifest.Tool tool, String flag, boolean supported) {
        paths.clear();
        List<String> args = new ArrayList<>(tool.path());
        if (tool.takesId()) {
            args.add("some-id");
        }
        args.add(flag);
        args.add("--limit".equals(flag) ? "3" : "x");

        int exitCode = run(args);
        boolean accepted = exitCode == ExitCodes.SUCCESS;
        if (accepted != supported) {
            failures.put(
                    tool.command() + " " + flag,
                    supported ? "rejected but the schema declares it" : "accepted but the schema does not");
        }
    }

    private int run(List<String> command) {
        List<String> args = new ArrayList<>();
        args.add("--url");
        args.add("http://127.0.0.1:" + server.getAddress().getPort());
        args.addAll(command);
        StringWriter sink = new StringWriter();
        return BootUiCli.run(
                args.toArray(new String[0]), Map.of(), true, new PrintWriter(sink, true), new PrintWriter(sink, true));
    }
}
