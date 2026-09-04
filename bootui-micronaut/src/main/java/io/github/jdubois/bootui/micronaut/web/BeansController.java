package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.BeanList;
import io.github.jdubois.bootui.engine.beans.BeansService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Beans panel ({@code GET /bootui/api/beans}).
 *
 * <p>A thin transport adapter over the shared engine {@link BeansService}, which owns the
 * framework-neutral sorting, classification / free-text filtering and paging. The bean enumeration,
 * self-data filtering and classification live in {@code MicronautBeanProvider}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/beans")
public class BeansController {

    private final BeansService beans;

    public BeansController(BeansService beans) {
        this.beans = beans;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public BeanList beans(
            @QueryValue @Nullable String q,
            @QueryValue @Nullable String classification,
            @QueryValue @Nullable Integer offset,
            @QueryValue @Nullable Integer limit) {
        return beans.beans(q, classification, offset, limit);
    }
}
