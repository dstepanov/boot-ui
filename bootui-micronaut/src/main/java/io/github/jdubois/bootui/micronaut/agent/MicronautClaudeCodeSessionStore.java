package io.github.jdubois.bootui.micronaut.agent;

import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.micronaut.context.env.Environment;

/**
 * The Claude Code panel's session store: the shared engine store bound to Micronaut configuration and a
 * Micronaut-side JSON parser. All the reading, parsing, bounding and watching logic lives in the engine.
 */
public class MicronautClaudeCodeSessionStore extends AgentSessionStore {

    public MicronautClaudeCodeSessionStore(Environment environment) {
        super(new MicronautClaudeCodeProperties(environment), new MicronautAgentJsonParser());
    }
}
