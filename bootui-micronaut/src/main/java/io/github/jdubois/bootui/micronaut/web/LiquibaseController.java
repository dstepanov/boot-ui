package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.LiquibaseActionRequest;
import io.github.jdubois.bootui.core.dto.LiquibaseReport;
import io.github.jdubois.bootui.engine.liquibase.LiquibaseService;
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
 * Controller for the Liquibase panel ({@code GET /bootui/api/liquibase/changesets} and the update action).
 *
 * <p>A thin transport adapter over the shared engine {@link LiquibaseService}. Everything runs on the
 * blocking executor: reading the change-log history and applying an update are both database work.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/liquibase")
@ExecuteOn(TaskExecutors.BLOCKING)
public class LiquibaseController {

    private final LiquibaseService service;

    public LiquibaseController(LiquibaseService service) {
        this.service = service;
    }

    @Get("/changesets")
    @Produces(MediaType.APPLICATION_JSON)
    public LiquibaseReport changeSets() {
        return service.report();
    }

    @Post("/update")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> update(@Body @Nullable LiquibaseActionRequest request) {
        var response = service.update(request);
        return HttpResponse.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}
