package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.DependenciesReport;
import io.github.jdubois.bootui.core.dto.DependencyDto;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.vulnerabilities.DependencyReports;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.github.jdubois.bootui.micronaut.vulnerabilities.MicronautDependencyProvider;
import io.github.jdubois.bootui.micronaut.vulnerabilities.OsvVulnerabilityScanner;
import io.micronaut.context.env.Environment;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.util.List;

/**
 * Controller for the Vulnerabilities panel ({@code GET /bootui/api/vulnerabilities},
 * {@code POST /bootui/api/vulnerabilities/scan}).
 *
 * <p>{@code GET} is strictly local: it lists the dependency inventory read from the runtime classpath and
 * never touches the network. Only the explicit scan action contacts OSV.dev, it is bounded by
 * configuration, and concurrent scans are collapsed into one so a repeated click cannot multiply outbound
 * requests. It runs on the blocking executor because it waits on network I/O.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/vulnerabilities")
@ExecuteOn(TaskExecutors.BLOCKING)
public class VulnerabilitiesController {

    private final MicronautDependencyProvider dependencyProvider;
    private final OsvVulnerabilityScanner vulnerabilityScanner;
    private final Environment environment;
    private final DismissedRulesStore dismissedRules;
    private final SingleFlightAction singleFlight = new SingleFlightAction();

    private volatile DependenciesReport lastScanReport;

    public VulnerabilitiesController(
            MicronautDependencyProvider dependencyProvider,
            OsvVulnerabilityScanner vulnerabilityScanner,
            Environment environment,
            DismissedRulesStore dismissedRules) {
        this.dependencyProvider = dependencyProvider;
        this.vulnerabilityScanner = vulnerabilityScanner;
        this.environment = environment;
        this.dismissedRules = dismissedRules;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public DependenciesReport dependencies() {
        DependenciesReport cached = this.lastScanReport;
        if (cached != null) {
            return DependencyReports.applyDismissals(cached, dismissedRules.load());
        }
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report = DependencyReports.report(
                osvEnabled(),
                "NOT_SCANNED",
                "Dependency inventory loaded. Click Scan with OSV.dev to check for known vulnerabilities.",
                null,
                0,
                dependencies);
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }

    @Post("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public DependenciesReport scan() {
        List<DependencyDto> dependencies = dependencyProvider.dependencies();
        DependenciesReport report;
        if (!osvEnabled()) {
            report = DependencyReports.report(
                    false,
                    "DISABLED",
                    "OSV scanning is disabled. Set bootui.vulnerabilities.osv-enabled=true to allow on-demand scans.",
                    null,
                    0,
                    dependencies);
        } else {
            report = singleFlight.run(
                    ActionOperations.VULNERABILITIES_SCAN, () -> vulnerabilityScanner.scan(dependencies));
        }
        if (!"DISABLED".equals(report.status())) {
            this.lastScanReport = report;
        }
        return DependencyReports.applyDismissals(report, dismissedRules.load());
    }

    private boolean osvEnabled() {
        return environment
                .getProperty("bootui.vulnerabilities.osv-enabled", Boolean.class)
                .orElse(Boolean.TRUE);
    }
}
