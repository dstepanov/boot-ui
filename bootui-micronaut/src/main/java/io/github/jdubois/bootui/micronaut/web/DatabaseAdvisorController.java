package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.DatabaseAdvisorReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.databaseadvisor.DatabaseAdvisorScanner;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Database advisor ({@code GET /bootui/api/database-advisor},
 * {@code POST /bootui/api/database-advisor/scan}).
 *
 * <p>{@code GET} replays the last scan so rendering the panel never touches the database; the scan itself —
 * which reads live metadata from each datasource — runs only on the explicit action, on the blocking
 * executor.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/database-advisor")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DatabaseAdvisorController {

    private final DatabaseAdvisorScanner scanner;
    private final DismissedRulesStore dismissedRules;

    private volatile DatabaseAdvisorReport lastReport;

    public DatabaseAdvisorController(DatabaseAdvisorScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseAdvisorReport databaseAdvisor() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public DatabaseAdvisorReport scan() {
        DatabaseAdvisorReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
