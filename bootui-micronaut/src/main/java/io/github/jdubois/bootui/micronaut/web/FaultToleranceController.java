package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.FaultToleranceReport;
import io.github.jdubois.bootui.engine.faulttolerance.FaultToleranceService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;

/**
 * Controller for the Fault Tolerance panel ({@code GET /bootui/api/fault-tolerance}).
 *
 * <p>A thin transport adapter over the shared engine {@link FaultToleranceService}, which merges the
 * configured policies with the events observed so far. Passive: it reads what has already been recorded and
 * never exercises a policy.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/fault-tolerance")
public class FaultToleranceController {

    private final FaultToleranceService service;

    public FaultToleranceController(FaultToleranceService service) {
        this.service = service;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public FaultToleranceReport faultTolerance() {
        return service.report();
    }
}
