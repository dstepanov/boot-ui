package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.OverviewDto;
import io.github.jdubois.bootui.micronaut.MicronautApplicationInfo;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the framework-neutral shell-chrome endpoint ({@code GET /bootui/api/overview}).
 *
 * <p>It returns the high-level {@link OverviewDto} the shared shell binds its header to. The shell needs
 * this data on every platform, independently of the client-side Overview dashboard panel (which aggregates
 * the advisor endpoints in the browser and never calls this endpoint). It is passive (read-only).
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/overview")
public class OverviewController {

    private final MicronautApplicationInfo info;

    public OverviewController(MicronautApplicationInfo info) {
        this.info = info;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public OverviewDto overview() {
        return info.overview();
    }
}
