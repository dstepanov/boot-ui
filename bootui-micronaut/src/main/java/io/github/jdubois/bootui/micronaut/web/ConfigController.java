package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ConfigReport;
import io.github.jdubois.bootui.engine.config.ConfigService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Configuration panel ({@code GET /bootui/api/config}).
 *
 * <p>A thin transport adapter over the shared engine {@link ConfigService}, which masks every value behind
 * the live exposure policy before it reaches the browser. The panel is read-only on Micronaut: unlike
 * Spring, there is no runtime-overrides property source to write to, which the manifest reports honestly
 * through its {@code readOnlyReason}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/config")
public class ConfigController {

    private final ConfigService configService;

    public ConfigController(ConfigService configService) {
        this.configService = configService;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ConfigReport list(
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String source,
            @QueryValue(defaultValue = "false") boolean overridesOnly,
            @QueryValue @Nullable Integer offset,
            @QueryValue @Nullable Integer limit) {
        return configService.list(q, source, overridesOnly, offset, limit);
    }
}
