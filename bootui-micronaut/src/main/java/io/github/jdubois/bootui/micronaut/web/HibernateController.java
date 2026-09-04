package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Hibernate (ORM mapping) advisor ({@code GET /bootui/api/hibernate},
 * {@code POST /bootui/api/hibernate/scan}).
 *
 * <p>{@code GET} replays the last scan; reading the JPA metamodel happens only on the explicit scan action,
 * on the blocking executor. When the application has no persistence unit the scan reports that honestly
 * rather than failing.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/hibernate")
@ExecuteOn(TaskExecutors.BLOCKING)
public class HibernateController {

    private final HibernateScanner scanner;
    private final DismissedRulesStore dismissedRules;

    private volatile HibernateReport lastReport;

    public HibernateController(HibernateScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport hibernate() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
