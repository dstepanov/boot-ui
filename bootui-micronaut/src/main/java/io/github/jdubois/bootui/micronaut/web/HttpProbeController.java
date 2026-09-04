package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HttpProbeRequest;
import io.github.jdubois.bootui.engine.web.HttpProbeService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.Map;

/**
 * Controller for the HTTP Probe panel ({@code POST /bootui/api/http-probe}).
 *
 * <p>The probe is a {@code POST} because it performs real work: the shared engine
 * {@link HttpProbeService} issues a bounded request against the application's own loopback port. Rendering
 * the panel never probes anything; only this explicit action does. It runs on the blocking executor since
 * it waits on a network round trip.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/http-probe")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HttpProbeController {

    private final HttpProbeService probeService;

    public HttpProbeController(HttpProbeService probeService) {
        this.probeService = probeService;
    }

    @Post
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<?> probe(@Body @Nullable HttpProbeRequest request) {
        try {
            return HttpResponse.ok(probeService.probe(request));
        } catch (IllegalArgumentException ex) {
            return HttpResponse.badRequest(
                    Map.of("error", ex.getMessage() == null ? "Invalid request" : ex.getMessage()));
        }
    }
}
