package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.CliServerStatus;
import io.github.jdubois.bootui.engine.cli.CliService;
import io.github.jdubois.bootui.engine.cli.CliToolResponse;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;

/**
 * The endpoint the {@code bootui} command-line client calls ({@code /bootui/api/cli}).
 *
 * <p>{@code GET} advertises the tools this application exposes, so the CLI can discover its own command set
 * from the running application rather than assuming one. Each tool is invoked by name, and the engine's
 * {@link CliService} owns the policy: a panel the operator disabled, or a write on a read-only panel, is
 * refused with the same status the HTTP surface would return, which is what lets a script branch on it.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/cli")
@ExecuteOn(TaskExecutors.BLOCKING)
public class BootUiCliController {

    private final CliService service;

    public BootUiCliController(CliService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public CliServerStatus status() {
        return service.status();
    }

    @Post("/tools/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> invoke(@PathVariable String name, @Body @Nullable Map<String, Object> arguments) {
        CliToolResponse response = service.invoke(name, arguments == null ? Map.of() : arguments);
        return HttpResponse.status(HttpStatus.valueOf(response.status().code()))
                .body(response.successful() ? response.payload() : Map.of("error", response.error()));
    }
}
