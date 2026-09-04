package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.ArchitectureReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.architecture.ArchitectureScanner;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Architecture (ArchUnit) advisor ({@code GET /bootui/api/architecture},
 * {@code POST /bootui/api/architecture/scan}).
 *
 * <p>{@code GET} replays the last scan so rendering the panel never imports bytecode; the ArchUnit import
 * — bounded to the application's own base packages — happens only on the explicit {@code POST /scan}
 * action, on the blocking executor.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/architecture")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ArchitectureController {

    private final ArchitectureScanner scanner;
    private final DismissedRulesStore dismissedRules;

    private volatile ArchitectureReport lastReport;

    public ArchitectureController(ArchitectureScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public ArchitectureReport architecture() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public ArchitectureReport scan() {
        ArchitectureReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
