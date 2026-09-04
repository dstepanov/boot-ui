package io.github.jdubois.bootui.micronaut.agent;

import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.micronaut.context.env.Environment;

/**
 * The Copilot panel's session store: the shared engine store bound to Micronaut configuration and a
 * Micronaut-side JSON parser. All the reading, parsing, bounding and watching logic lives in the engine.
 */
public class MicronautCopilotSessionStore extends AgentSessionStore {

    public MicronautCopilotSessionStore(Environment environment) {
        super(new MicronautCopilotProperties(environment), new MicronautAgentJsonParser());
    }
}
