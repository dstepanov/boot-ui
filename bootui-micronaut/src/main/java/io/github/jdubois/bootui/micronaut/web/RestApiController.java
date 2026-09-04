package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ErrorContractReport;
import io.github.jdubois.bootui.core.dto.RestApiReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.errorcontract.ErrorContractService;
import io.github.jdubois.bootui.engine.restapi.RestApiScanner;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the REST API advisor ({@code GET /bootui/api/rest-api}, its error-contract view, and
 * {@code POST /bootui/api/rest-api/scan}).
 *
 * <p>{@code GET} replays the last scan so rendering the panel never imports bytecode; the ArchUnit import —
 * bounded to the application's own base packages — happens only on the explicit scan action, on the
 * blocking executor. The error-contract view is a separate, cheap read over
 * {@link ErrorContractService}, whose Micronaut discovery reads {@code @Error} methods and
 * {@code ExceptionHandler} beans from the container.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/rest-api")
@ExecuteOn(TaskExecutors.BLOCKING)
public class RestApiController {

    private final RestApiScanner scanner;
    private final DismissedRulesStore dismissedRules;
    private final ErrorContractService errorContract;

    private volatile RestApiReport lastReport;

    public RestApiController(
            RestApiScanner scanner, DismissedRulesStore dismissedRules, ErrorContractService errorContract) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.errorContract = errorContract;
        this.lastReport = scanner.initialReport();
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public RestApiReport restApi() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @Get("/error-contract")
    @Produces(MediaType.APPLICATION_JSON)
    public ErrorContractReport errorContract(
            @QueryValue @Nullable String q, @QueryValue @Nullable Integer offset, @QueryValue @Nullable Integer limit) {
        return errorContract.report(q, offset, limit);
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public RestApiReport scan() {
        RestApiReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
