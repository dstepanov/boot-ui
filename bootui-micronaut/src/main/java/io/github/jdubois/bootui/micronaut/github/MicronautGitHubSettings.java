package io.github.jdubois.bootui.micronaut.github;

import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import java.time.Duration;
import java.util.List;

/**
 * The immutable bounds the GitHub panel's API calls run under, mapped once from {@code bootui.github.*}.
 *
 * <p>Every default matches the Spring and Quarkus adapters. The bounds exist because this is the one panel
 * that talks to a third party: the number of API calls per refresh is capped, each response is capped, the
 * request timeout is short, and calls stop entirely once the account's remaining rate-limit quota drops below
 * the safety threshold — so BootUI can never exhaust a developer's GitHub quota on their behalf. The host
 * allow-list means a redirected or misconfigured base URL cannot send a token anywhere else.
 */
public record MicronautGitHubSettings(
        Duration requestTimeout,
        int maxPullRequests,
        int maxIssues,
        int maxWorkflowRuns,
        int quotaSafetyThreshold,
        int maxApiCalls,
        int maxSecurityAlerts,
        List<String> allowedApiHosts) {

    public static final String DEFAULT_API_HOST = "api.github.com";

    public static MicronautGitHubSettings from(Environment environment) {
        return new MicronautGitHubSettings(
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
                .map(raw -> java.util.Arrays.stream(raw.split(","))
                        .map(String::trim)
                        .filter(host -> !host.isBlank())
                        .toList())
                .filter(parsed -> !parsed.isEmpty())
                .orElseGet(() -> List.of(DEFAULT_API_HOST));
    }
}
