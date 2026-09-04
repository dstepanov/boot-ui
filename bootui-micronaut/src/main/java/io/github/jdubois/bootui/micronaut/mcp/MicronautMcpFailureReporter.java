package io.github.jdubois.bootui.micronaut.mcp;

import io.github.jdubois.bootui.engine.mcp.McpFailureReporter;
import jakarta.inject.Singleton;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Micronaut logging bridge for unexpected failures contained by the shared MCP boundary. */
@io.github.jdubois.bootui.micronaut.RequiresBootUi
@Singleton
public class MicronautMcpFailureReporter implements McpFailureReporter {

    private static final Logger LOG = Logger.getLogger(MicronautMcpFailureReporter.class.getName());

    @Override
    public void report(String operation, Throwable failure) {
        LOG.log(Level.SEVERE, "BootUI MCP failure while " + operation, failure);
    }
}
