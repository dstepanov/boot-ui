package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.HibernateReport;
import io.github.jdubois.bootui.core.dto.HibernateStatisticsReport;
import io.github.jdubois.bootui.engine.advisor.DismissedRulesStore;
import io.github.jdubois.bootui.engine.hibernate.HibernateScanner;
import io.github.jdubois.bootui.engine.hibernate.HibernateStatisticsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Hibernate (ORM mapping) advisor panel ({@code GET /bootui/api/hibernate},
 * {@code POST /bootui/api/hibernate/scan}) and the additive Hibernate Session Monitoring panel
 * ({@code GET /bootui/api/hibernate/statistics}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code HibernateController}: a thin transport adapter over
 * the shared engine {@link HibernateScanner}, which reads the JPA metamodel and runs a curated registry of
 * static Hibernate best-practice checks against the host application's mapped entities. {@code GET} returns
 * the last report (initially "not scanned"); {@code POST /scan} reads the metamodel and evaluates the rules,
 * caching the result. Dismissed rule IDs from the shared {@link DismissedRulesStore} are applied on read,
 * exactly as on Spring.</p>
 *
 * <p>{@code GET /statistics} is a thin transport adapter over the shared engine
 * {@link HibernateStatisticsService}, which reports the panel unavailable (rather than faking data) when no
 * {@code SessionFactory} is reachable or {@code hibernate.generate_statistics} is disabled.</p>
 *
 * <p>The resource is produced <em>unconditionally</em> and the engine {@code HibernateScanner} /
 * {@code HibernateStatisticsService} are always wired (neither holds a {@code jakarta.persistence} type):
 * when {@code quarkus-hibernate-orm} is absent the scanner's entity-discovery source is unsatisfied, so
 * {@code POST /scan} renders a DISABLED report, and the statistics service's provider is unsatisfied, so
 * {@code GET /statistics} renders the panel unavailable — neither fails. Availability of the <em>panel</em>
 * in the manifest, by contrast, tracks the {@code HIBERNATE_ORM} capability (see
 * {@code QuarkusPanelAvailability}).</p>
 *
 * <p>It is {@code @ApplicationScoped} (not the default per-request scope) because it caches the last report
 * in a {@code volatile} field across requests — the CDI analogue of the Spring controller's singleton with a
 * {@code volatile lastReport}.</p>
 */
@ApplicationScoped
@Path("/bootui/api/hibernate")
public class HibernateResource {

    private final HibernateScanner scanner;

    private final DismissedRulesStore dismissedRules;

    private final HibernateStatisticsService statisticsService;

    private volatile HibernateReport lastReport;

    @Inject
    public HibernateResource(
            HibernateScanner scanner,
            DismissedRulesStore dismissedRules,
            HibernateStatisticsService statisticsService) {
        this.scanner = scanner;
        this.dismissedRules = dismissedRules;
        this.statisticsService = statisticsService;
        this.lastReport = scanner.initialReport();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport hibernate() {
        return scanner.applyDismissals(lastReport, dismissedRules.load());
    }

    @POST
    @Path("/scan")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateReport scan() {
        HibernateReport report = scanner.scan();
        lastReport = report;
        return scanner.applyDismissals(report, dismissedRules.load());
    }

    @GET
    @Path("/statistics")
    @Produces(MediaType.APPLICATION_JSON)
    public HibernateStatisticsReport statistics() {
        return statisticsService.report();
    }
}
