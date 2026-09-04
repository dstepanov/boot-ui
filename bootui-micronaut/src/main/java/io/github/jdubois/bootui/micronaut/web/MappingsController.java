package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.MappingsReport;
import io.github.jdubois.bootui.engine.mappings.MappingsService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Mappings panel ({@code GET /bootui/api/mappings} and its {@code /flat} variant).
 *
 * <p>A thin transport adapter over the shared engine {@link MappingsService}; the live route enumeration
 * lives in {@code MicronautMappingProvider}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/mappings")
public class MappingsController {

    private final MappingsService mappings;

    public MappingsController(MappingsService mappings) {
        this.mappings = mappings;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public MappingsReport mappings(
            @QueryValue @Nullable String q, @QueryValue @Nullable Integer offset, @QueryValue @Nullable Integer limit) {
        return mappings.report(q, offset, limit);
    }

    @Get("/flat")
    @Produces(MediaType.APPLICATION_JSON)
    public MappingsReport flatMappings(
            @QueryValue @Nullable String q, @QueryValue @Nullable Integer offset, @QueryValue @Nullable Integer limit) {
        return mappings.report(q, offset, limit);
    }
}
