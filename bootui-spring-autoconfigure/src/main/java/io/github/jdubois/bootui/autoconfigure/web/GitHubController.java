package io.github.jdubois.bootui.autoconfigure.web;

import io.github.jdubois.bootui.autoconfigure.BootUiProperties;
import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.engine.github.DefaultGitHubTokenProvider;
import io.github.jdubois.bootui.engine.github.GitHubApiClient;
import io.github.jdubois.bootui.engine.github.GitHubApiSettings;
import io.github.jdubois.bootui.engine.github.GitHubDashboardConfig;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${bootui.api-path:${bootui.path:/bootui}/api}/github")
public class GitHubController {

    private final GitHubDashboardService service;

    /**
     * Composition root for the GitHub panel. The client itself is the shared engine
     * {@link GitHubApiClient}; this factory only maps {@code bootui.github.*} onto its neutral
     * {@link GitHubApiSettings} and hands it a Jackson 3 {@link SpringJsonCodec}, exactly as the Quarkus and
     * Micronaut factories do with their own Jackson 2 codecs.
     *
     * <p>The host allow-list is read once and passed to <em>both</em> the engine {@link GitHubDashboardConfig}
     * (consulted during repository detection) and the client's settings (enforced before any request is
     * issued). {@code Arrays.asList} keeps it null-safe. No network call happens at construction or on render:
     * only the explicit {@code POST /bootui/api/github/refresh} action calls GitHub.</p>
     */
    @Autowired
    public GitHubController(BootUiProperties properties) {
        this(properties, settings(properties.getGithub()));
    }

    private GitHubController(BootUiProperties properties, GitHubApiSettings settings) {
        this(GitHubDashboardService.using(
                Path.of(System.getProperty("user.dir", ".")),
                new GitHubDashboardConfig(properties.getGithub().isApiEnabled(), settings.allowedApiHosts()),
                new GitHubApiClient(
                        settings,
                        HttpClient.newBuilder()
                                .connectTimeout(settings.requestTimeout())
                                .build(),
                        new SpringJsonCodec(),
                        DefaultGitHubTokenProvider.create())));
    }

    private static GitHubApiSettings settings(BootUiProperties.GitHub github) {
        List<String> allowedApiHosts = Arrays.asList(github.getAllowedApiHosts());
        return new GitHubApiSettings(
                github.getRequestTimeout(),
                github.getMaxPullRequests(),
                github.getMaxIssues(),
                github.getMaxWorkflowRuns(),
                github.getQuotaSafetyThreshold(),
                github.getMaxApiCalls(),
                github.getMaxSecurityAlerts(),
                allowedApiHosts);
    }

    GitHubController(GitHubDashboardService service) {
        this.service = service;
    }

    @GetMapping
    public GitHubDashboardReport dashboard() {
        return service.dashboard();
    }

    @PostMapping("/refresh")
    public GitHubDashboardReport refresh() {
        return service.refresh();
    }
}
