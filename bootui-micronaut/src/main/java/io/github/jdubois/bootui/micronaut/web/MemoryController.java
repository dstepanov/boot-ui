package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.MemoryReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.memory.MemoryScanner;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

/**
 * Controller for the Memory advisor ({@code GET /bootui/api/memory}, {@code POST /bootui/api/memory/scan}).
 *
 * <p>{@code GET} replays the last scan (or the scanner's cheap initial report) so rendering the panel never
 * does work; the heap-content histogram forces a full GC, so it happens only on the explicit
 * {@code POST /scan} action, on the blocking executor.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/memory")
@ExecuteOn(TaskExecutors.BLOCKING)
public class MemoryController {

    private final MemoryScanner scanner;
    private final DismissedRulesStore dismissedRules;

    private volatile MemoryReport lastReport;

    public MemoryController(MemoryScanner scanner, DismissedRulesStore dismissedRules) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.lastReport = scanner.initialReport();
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport memory() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public MemoryReport scan() {
        MemoryReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }
}
