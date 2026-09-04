package io.github.jdubois.bootui.micronaut.github;

import io.github.jdubois.bootui.engine.github.GitHubApiSettings;
import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Maps {@code bootui.github.*} from the Micronaut {@link Environment} onto the engine's neutral
 * {@link GitHubApiSettings}.
 *
 * <p>This is the only GitHub-panel code the Micronaut adapter owns: the client itself is the shared engine
 * {@code GitHubApiClient}, so all that remains per adapter is reading configuration in the framework's own
 * idiom. Every default matches the Spring and Quarkus adapters key for key, which is what makes the same
 * configuration behave identically on every stack.</p>
 */
public final class MicronautGitHubSettings {

    public static final String DEFAULT_API_HOST = "api.github.com";

    private MicronautGitHubSettings() {}

    public static GitHubApiSettings from(Environment environment) {
        return new GitHubApiSettings(
                environment
                        .getProperty("bootui.github.request-timeout", Duration.class)
                        .orElse(Duration.ofSeconds(5)),
                environment
                        .getProperty("bootui.github.max-pull-requests", Integer.class)
                        .orElse(10),
                environment
                        .getProperty("bootui.github.max-issues", Integer.class)
                        .orElse(25),
                environment
                        .getProperty("bootui.github.max-workflow-runs", Integer.class)
                        .orElse(20),
                environment
                        .getProperty("bootui.github.quota-safety-threshold", Integer.class)
                        .orElse(10),
                environment
                        .getProperty("bootui.github.max-api-calls", Integer.class)
                        .orElse(17),
                environment
                        .getProperty("bootui.github.max-security-alerts", Integer.class)
                        .orElse(50),
                allowedApiHosts(environment));
    }

    /** The API hosts the client may talk to. Accepts a YAML list or a comma-separated string. */
    public static List<String> allowedApiHosts(Environment environment) {
        List<String> hosts = environment
                .getProperty("bootui.github.allowed-api-hosts", Argument.listOf(String.class))
                .orElse(null);
        if (hosts != null && !hosts.isEmpty()) {
            return hosts.stream()
                    .map(String::trim)
                    .filter(host -> !host.isBlank())
                    .toList();
        }
        return environment
                .getProperty("bootui.github.allowed-api-hosts", String.class)
                .map(raw -> Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(host -> !host.isBlank())
                        .toList())
                .filter(parsed -> !parsed.isEmpty())
                .orElseGet(() -> List.of(DEFAULT_API_HOST));
    }
}
