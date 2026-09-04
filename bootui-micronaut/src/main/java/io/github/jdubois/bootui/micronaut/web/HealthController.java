package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HealthNodeDto;
import io.github.jdubois.bootui.engine.health.HealthService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Health panel ({@code GET /bootui/api/health}).
 *
 * <p>A thin transport adapter over the shared engine {@link HealthService}. It runs on the blocking
 * executor because aggregating the application's health indicators may touch real dependencies (a
 * datasource, a message broker); when {@code micronaut-management} is absent the service instead renders
 * the Micronaut setup guidance, with no I/O at all.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/health")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HealthController {

    private final HealthService health;

    public HealthController(HealthService health) {
        this.health = health;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HealthNodeDto health() {
        return health.health();
    }
}
