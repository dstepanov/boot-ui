package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.LoggerDto;
import io.github.jdubois.bootui.core.dto.LoggersReport;
import io.github.jdubois.bootui.engine.loggers.LoggersService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Loggers panel ({@code GET /bootui/api/loggers} and the per-logger level write).
 *
 * <p>A thin transport adapter over the shared engine {@link LoggersService}, which owns the self-logger
 * filtering, sorting, paging and the write guard that keeps BootUI's own loggers off-limits. The Logback
 * interaction lives in {@code MicronautLoggerProvider}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/loggers")
public class LoggersController {

    private final LoggersService loggers;

    public LoggersController(LoggersService loggers) {
        this.loggers = loggers;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public LoggersReport loggers(
            @QueryValue @Nullable String q, @QueryValue @Nullable Integer offset, @QueryValue @Nullable Integer limit) {
        return loggers.report(q, offset, limit);
    }

    /**
     * Sets one logger's level. A logger the engine's write guard protects (BootUI's own) or refuses to
     * resolve is reported by the engine itself — as a {@link io.github.jdubois.bootui.core.BootUiException}
     * for a blocked write, and as {@code null} for an unknown logger, which becomes a 404 here.
     */
    @Post("/{name}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<LoggerDto> setLevel(@PathVariable String name, @Body @Nullable LevelUpdateRequest request) {
        LoggerDto updated = loggers.setLevel(name, request == null ? null : request.level());
        return updated == null ? HttpResponse.notFound() : HttpResponse.ok(updated);
    }

    /** The request body of a level write, matching the shared UI's payload on every adapter. */
    public record LevelUpdateRequest(String level) {}
}
