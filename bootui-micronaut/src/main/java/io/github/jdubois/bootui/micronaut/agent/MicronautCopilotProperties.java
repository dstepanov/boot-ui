package io.github.jdubois.bootui.micronaut.agent;

import io.micronaut.context.env.Environment;
import java.nio.file.Path;

/** Copilot CLI session-state configuration, at parity with the Spring and Quarkus adapters. */
public class MicronautCopilotProperties extends MicronautAgentSessionProperties {

    private final Environment environment;

    public MicronautCopilotProperties(Environment environment) {
        super("copilot", environment);
        this.environment = environment;
    }

    @Override
    public Path defaultSessionStateDir() {
        return home(".copilot", "session-state");
    }

    @Override
    public boolean isAllowRawReveal() {
        return environment
                .getProperty("bootui.copilot.allow-raw-reveal", Boolean.class)
                .orElse(true);
    }

    @Override
    public String getPanelTitle() {
        return "Copilot";
    }

    @Override
    public String getSessionSourceName() {
        return "Copilot CLI";
    }

    @Override
    public String getWatcherThreadName() {
        return "bootui-copilot-watcher";
    }

    @Override
    public boolean isProjectSessionDirectoryLayout() {
        return false;
    }
}
