package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HikariPoolSnapshotDto;
import io.github.jdubois.bootui.core.dto.HikariPoolsReport;
import io.github.jdubois.bootui.engine.datasource.ConnectionPoolService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the Database Connection Pools panel ({@code GET /bootui/api/database-connection-pools/pools}
 * and the per-pool live snapshot).
 *
 * <p>A thin transport adapter over the shared engine {@link ConnectionPoolService}, which masks the JDBC URL
 * and credentials behind the live exposure policy. The pool reading itself lives in
 * {@code MicronautHikariConnectionPoolProvider}.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/database-connection-pools")
public class ConnectionPoolsController {

    private final ConnectionPoolService service;

    public ConnectionPoolsController(ConnectionPoolService service) {
        this.service = service;
    }

    @Get("/pools")
    @Produces(MediaType.APPLICATION_JSON)
    public HikariPoolsReport pools() {
        return service.report();
    }

    @Get("/pools/{name}/snapshot")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<HikariPoolSnapshotDto> snapshot(@PathVariable String name) {
        HikariPoolSnapshotDto snapshot = service.snapshot(name);
        return snapshot == null ? HttpResponse.notFound() : HttpResponse.ok(snapshot);
    }
}
