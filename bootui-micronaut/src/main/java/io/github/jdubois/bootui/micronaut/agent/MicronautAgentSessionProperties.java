package io.github.jdubois.bootui.micronaut.agent;

import io.github.jdubois.bootui.spi.agent.AgentSessionProperties;
import io.micronaut.context.env.Environment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Locale;

/**
 * The configuration shared by the agent-session panels (Copilot and Claude Code) on Micronaut.
 *
 * <p>Every key, default and bound matches the Spring and Quarkus adapters, so the same configuration sizes
 * and gates these panels identically on every stack. The bounds matter: an agent's session directory can be
 * arbitrarily large, so the number of sessions, the number of parsed sessions and the events per session are
 * all capped before anything is read into memory.
 */
abstract class MicronautAgentSessionProperties implements AgentSessionProperties {

    private final String prefix;
    private final Environment environment;

    MicronautAgentSessionProperties(String prefix, Environment environment) {
        this.prefix = prefix;
        this.environment = environment;
    }

    private String mode() {
        return environment
                .getProperty("bootui." + prefix + ".enabled", String.class)
                .orElse("AUTO")
                .trim()
                .toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean enabledOn() {
        return "ON".equals(mode());
    }

    @Override
    public boolean enabledAuto() {
        return "AUTO".equals(mode());
    }

    @Override
    public String getSessionStateDir() {
        return environment
                .getProperty("bootui." + prefix + ".session-state-dir", String.class)
                .orElse(null);
    }

    @Override
    public int getMaxEventsPerSession() {
        return environment
                .getProperty("bootui." + prefix + ".max-events-per-session", Integer.class)
                .orElse(2000);
    }

    @Override
    public int getMaxSessions() {
        return environment
                .getProperty("bootui." + prefix + ".max-sessions", Integer.class)
                .orElse(100);
    }

    @Override
    public int getMaxParsedSessions() {
        return environment
                .getProperty("bootui." + prefix + ".max-parsed-sessions", Integer.class)
                .orElse(100);
    }

    @Override
    public Duration getStreamDebounce() {
        return environment
                .getProperty("bootui." + prefix + ".stream-debounce", Duration.class)
                .orElse(Duration.ofMillis(400));
    }

    @Override
    public String maxParsedSessionsPropertyName() {
        return "bootui." + prefix + ".max-parsed-sessions";
    }

    static Path home(String... segments) {
        return Paths.get(System.getProperty("user.home", ""), segments);
    }
}
