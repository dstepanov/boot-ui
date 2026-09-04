package io.github.jdubois.bootui.micronaut.agent;

import io.micronaut.context.env.Environment;
import java.nio.file.Path;

/**
 * Claude Code session-state configuration, at parity with the Spring and Quarkus adapters.
 *
 * <p>Raw reveal is off and not configurable here, exactly as on the other adapters: a Claude Code transcript
 * can contain the contents of the developer's own source files, which BootUI will not hand back verbatim.
 */
public class MicronautClaudeCodeProperties extends MicronautAgentSessionProperties {

    public MicronautClaudeCodeProperties(Environment environment) {
        super("claude-code", environment);
    }

    @Override
    public Path defaultSessionStateDir() {
        return home(".claude", "projects");
    }

    @Override
    public boolean isAllowRawReveal() {
        return false;
    }

    @Override
    public String getPanelTitle() {
        return "Claude Code";
    }

    @Override
    public String getSessionSourceName() {
        return "Claude Code";
    }

    @Override
    public String getWatcherThreadName() {
        return "bootui-claude-code-watcher";
    }

    @Override
    public boolean isProjectSessionDirectoryLayout() {
        return true;
    }
}
