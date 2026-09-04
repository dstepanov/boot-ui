package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.FlywayActionRequest;
import io.github.jdubois.bootui.core.dto.FlywayReport;
import io.github.jdubois.bootui.engine.flyway.FlywayService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Flyway panel ({@code GET /bootui/api/flyway/migrations} plus the migrate and clean
 * actions).
 *
 * <p>A thin transport adapter over the shared engine {@link FlywayService}, which owns the report shape and
 * the action guards — including refusing a clean the provider reports as disabled. Everything runs on the
 * blocking executor: reading migration history and running a migration are both database work.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/flyway")
@ExecuteOn(TaskExecutors.BLOCKING)
public class FlywayController {

    private final FlywayService service;

    public FlywayController(FlywayService service) {
        this.service = service;
    }

    @Get("/migrations")
    @Produces(MediaType.APPLICATION_JSON)
    public FlywayReport migrations() {
        return service.report();
    }

    @Post("/migrate")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> migrate(@Body @Nullable FlywayActionRequest request) {
        var response = service.migrate(request);
        return HttpResponse.status(HttpStatus.valueOf(response.status())).body(response.body());
    }

    @Post("/clean")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clean(@Body @Nullable FlywayActionRequest request) {
        var response = service.clean(request);
        return HttpResponse.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}
