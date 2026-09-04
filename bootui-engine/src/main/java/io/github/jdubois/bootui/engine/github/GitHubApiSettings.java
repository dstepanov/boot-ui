package io.github.jdubois.bootui.engine.github;

import java.time.Duration;
import java.util.List;

/**
 * The immutable bounds {@link GitHubApiClient} runs its API calls under, mapped once by each adapter from the
 * same {@code bootui.github.*} keys with the same defaults.
 *
 * <p>This follows the engine's <em>static settings record</em> template rather than a live-policy SPI: the
 * GitHub panel has no UI override or re-bind path, so the values are resolved at startup and passed as one
 * immutable carrier. It deliberately holds only what the <em>client</em> needs; the engine
 * {@link GitHubDashboardService} is configured separately through {@link GitHubDashboardConfig}, and adapters
 * read the host allow-list once and hand the same list to both.</p>
 *
 * <p>The bounds exist because this is the one panel that talks to a third party: the number of API calls per
 * refresh is capped, the request timeout is short, each section's result count is capped, and calls stop
 * entirely once the account's remaining rate-limit quota drops below the safety threshold — so BootUI can
 * never exhaust a developer's GitHub quota on their behalf. The host allow-list means a redirected or
 * misconfigured base URL cannot send a token anywhere else.</p>
 */
public record GitHubApiSettings(
        Duration requestTimeout,
        int maxPullRequests,
        int maxIssues,
        int maxWorkflowRuns,
        int quotaSafetyThreshold,
        int maxApiCalls,
        int maxSecurityAlerts,
        List<String> allowedApiHosts) {}
