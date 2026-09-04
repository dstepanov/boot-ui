package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the Hibernate Statistics panel ({@code GET /bootui/api/hibernate-statistics} and the enable
 * action).
 *
 * <p>A thin transport adapter over the shared engine {@link HibernateStatisticsService}. Hibernate collects
 * statistics only when they are switched on, which costs measurable overhead, so enabling them is an explicit
 * action rather than something the panel does on render.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/hibernate-statistics")
public class HibernateStatisticsController {

    private final HibernateStatisticsService statisticsService;

    public HibernateStatisticsController(HibernateStatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateStatisticsReport statistics() {
        return statisticsService.report();
    }

    @Post("/enable")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateStatisticsReport enable() {
        return statisticsService.enable();
    }
}
