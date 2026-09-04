package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.CacheClearRequest;
import io.github.jdubois.bootui.core.dto.CacheReport;
import io.github.jdubois.bootui.engine.cache.CacheService;
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

/**
 * Controller for the Cache panel ({@code GET /bootui/api/cache}, {@code POST /bootui/api/cache/clear}).
 *
 * <p>A thin transport adapter over the shared engine {@link CacheService}, which owns the report shape and
 * the clear semantics — including refusing a clear the provider cannot perform, with its own status and
 * message. The cache reading and eviction live in {@code MicronautCacheProvider}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/cache")
public class CacheController {

    private final CacheService service;

    public CacheController(CacheService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public CacheReport cache() {
        return service.report();
    }

    @Post("/clear")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> clear(@Body @Nullable CacheClearRequest request) {
        var response = service.clear(request);
        return HttpResponse.status(HttpStatus.valueOf(response.status())).body(response.body());
    }
}
