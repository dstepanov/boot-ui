package io.github.jdubois.bootui.quarkus.web;

import io.github.jdubois.bootui.core.dto.ResilienceReport;
import io.github.jdubois.bootui.engine.resilience.ResilienceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * JAX-RS resource for the Resilience panel ({@code GET /bootui/api/resilience}).
 *
 * <p>The Quarkus analogue of the Spring adapter's {@code ResilienceController}: a thin, read-only transport
 * adapter over the shared engine {@link ResilienceService}, which orders, caps and wraps the policies supplied
 * by the (Quarkus) {@code ResiliencePolicyProvider} plus the bounded capture buffer. There is no write path —
 * opening or resetting a circuit breaker is explicitly out of scope — so the resource carries no
 * {@code LocalhostGuard} write floor.</p>
 *
 * <p>The resource is produced unconditionally and the engine service is always wired (it holds no
 * fault-tolerance types): when {@code quarkus-smallrye-fault-tolerance} is absent the provider reports
 * unavailable and the engine renders an empty report with {@code resiliencePresent=false}. Availability of
 * the <em>panel</em> in the manifest, by contrast, tracks the build-time
 * {@code bootui.internal.resilience-present} flag (see {@code QuarkusPanelAvailability}).</p>
 */
@Path("/bootui/api/resilience")
public class ResilienceResource {

    private final ResilienceService resilienceService;

    @Inject
    public ResilienceResource(ResilienceService resilienceService) {
        this.resilienceService = resilienceService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public ResilienceReport resilience() {
        return resilienceService.report();
    }
}
