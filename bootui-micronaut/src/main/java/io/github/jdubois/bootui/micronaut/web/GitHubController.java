package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.GitHubDashboardReport;
import io.github.jdubois.bootui.engine.github.GitHubDashboardService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the GitHub panel ({@code GET /bootui/api/github}, {@code POST /bootui/api/github/refresh}).
 *
 * <p>A thin transport adapter over the shared engine {@link GitHubDashboardService}. {@code GET} serves the
 * cached dashboard and never calls GitHub; only the explicit refresh does, bounded by
 * {@code MicronautGitHubSettings}. Both run on the blocking executor since a refresh waits on network I/O.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/github")
@ExecuteOn(TaskExecutors.BLOCKING)
public class GitHubController {

    private final GitHubDashboardService service;

    public GitHubController(GitHubDashboardService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public GitHubDashboardReport dashboard() {
        return service.dashboard();
    }

    @Post("/refresh")
    @Produces(MediaType.APPLICATION_JSON)
    public GitHubDashboardReport refresh() {
        return service.refresh();
    }
}
