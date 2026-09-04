package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ProfilesReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the Profile Diff panel ({@code GET /bootui/api/profile-diff}).
 *
 * <p>A thin transport adapter over the shared engine {@link ConfigService}, which diffs the per-profile
 * property sources. On Micronaut those are the environment-specific configuration files
 * ({@code application-dev.yml} and friends), which {@code MicronautConfigProvider} reports as profile
 * sources — so the panel shows what each active environment actually changes.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/profile-diff")
public class ProfileDiffController {

    private final ConfigService configService;

    public ProfileDiffController(ConfigService configService) {
        this.configService = configService;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ProfilesReport profiles() {
        return configService.profiles();
    }
}
