package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.BootUiClient;
import io.github.jdubois.bootui.client.BootUiClientOptions;
import java.io.PrintWriter;
import java.util.Map;
import java.util.function.Function;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;

/**
 * The {@code bootui} command-line entry point.
 *
 * <p>Every subcommand is one BootUI MCP tool, projected mechanically from the registry, so the CLI can never
 * offer a diagnostic the MCP server does not, or lack one it does.
 */
public final class BootUiCli {

    private BootUiCli() {}

    public static void main(String[] args) {
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        int exitCode = run(args, System.getenv(), System.console() != null, out, err);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    /**
     * Runs one command line.
     *
     * <p>Separate from {@link #main} so tests drive the CLI in-process with their own streams, environment,
     * and terminal answer, instead of forking a JVM and asserting on captured output.
     */
    public static int run(
            String[] args, Map<String, String> environment, boolean terminal, PrintWriter out, PrintWriter err) {
        return run(args, environment, terminal, out, err, BootUiClient::new);
    }

    static int run(
            String[] args,
            Map<String, String> environment,
            boolean terminal,
            PrintWriter out,
            PrintWriter err,
            Function<BootUiClientOptions, BootUiClient> clients) {
        CliContext context = new CliContext(ToolManifest.bundled(), environment, terminal, out, err, clients);
        CommandSpec root = CommandTree.build(context);
        root.version("bootui " + version());

        CommandLine commandLine = new CommandLine(root)
                .setOut(out)
                .setErr(err)
                .setColorScheme(CommandLine.Help.defaultColorScheme(
                        // AUTO already honours NO_COLOR and a dumb terminal; OFF is for the piped case,
                        // where escape codes would end up in whatever is reading the output.
                        terminal ? CommandLine.Help.Ansi.AUTO : CommandLine.Help.Ansi.OFF))
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((exception, failed, parseResult) -> {
                    // Anything reaching here is a bug or an environment failure rather than a refusal, so it
                    // gets one clear line, and the whole trace only when asked.
                    err.println(exception.getMessage() == null ? exception.toString() : exception.getMessage());
                    if (context.options().verbose()) {
                        exception.printStackTrace(err);
                    }
                    err.flush();
                    return ExitCodes.ERROR;
                });
        return commandLine.execute(args);
    }

    private static String version() {
        String version = BootUiCli.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }
}
