package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ScheduledReport;
import io.github.jdubois.bootui.engine.scheduled.ScheduledTasksService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the Scheduled Tasks panel ({@code GET /bootui/api/scheduled}).
 *
 * <p>A thin transport adapter over the shared engine {@link ScheduledTasksService}; the {@code @Scheduled}
 * inventory lives in {@code MicronautScheduledTaskProvider}, which reads it from compile-time bean metadata.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/scheduled")
public class ScheduledController {

    private final ScheduledTasksService scheduledTasksService;

    public ScheduledController(ScheduledTasksService scheduledTasksService) {
        this.scheduledTasksService = scheduledTasksService;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ScheduledReport scheduled() {
        return scheduledTasksService.report();
    }
}
