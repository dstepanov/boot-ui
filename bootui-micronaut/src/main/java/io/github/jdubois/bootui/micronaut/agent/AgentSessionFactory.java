package io.github.jdubois.bootui.micronaut.agent;

import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.env.Environment;
import jakarta.inject.Singleton;

/**
 * Creates the agent-session stores and ties their file watchers to the bean lifecycle.
 *
 * <p>Each store watches an agent's session directory on a background thread, so it must be stopped when the
 * context shuts down — otherwise a test context or a restarted application would leak a watcher thread. The
 * {@code preDestroy} binding is what guarantees that, and it is the reason these two beans are created here
 * rather than annotated directly.
 *
 * <p>A store is only started when its own configuration enables it, so an application that has never run
 * either agent pays nothing: no thread, no directory scan.
 */
@RequiresBootUi
@Factory
public class AgentSessionFactory {

    @Singleton
    @Bean(preDestroy = "stop")
    MicronautCopilotSessionStore copilotSessionStore(Environment environment) {
        MicronautCopilotSessionStore store = new MicronautCopilotSessionStore(environment);
        if (store.isStartEnabled()) {
            store.start();
        }
        return store;
    }

    @Singleton
    @Bean(preDestroy = "stop")
    MicronautClaudeCodeSessionStore claudeCodeSessionStore(Environment environment) {
        MicronautClaudeCodeSessionStore store = new MicronautClaudeCodeSessionStore(environment);
        if (store.isStartEnabled()) {
            store.start();
        }
        return store;
    }
}
