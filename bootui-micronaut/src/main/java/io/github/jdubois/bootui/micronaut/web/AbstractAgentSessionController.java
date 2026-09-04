package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.CopilotDashboardDto;
import io.github.jdubois.bootui.core.dto.CopilotEventListDto;
import io.github.jdubois.bootui.core.dto.CopilotRawEventDto;
import io.github.jdubois.bootui.core.dto.CopilotSessionDetail;
import io.github.jdubois.bootui.core.dto.CopilotSessionListDto;
import io.github.jdubois.bootui.engine.agent.AgentSessionStore;
import io.github.jdubois.bootui.micronaut.MicronautExposurePolicy;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * The endpoints the two agent-session panels (Copilot and Claude Code) share.
 *
 * <p>Both read the same shaped data from the same shared engine {@link AgentSessionStore}, so the transport
 * layer is written once here and each panel only binds it to its own mount and store.
 *
 * <p>The raw-event endpoint is deliberately double-gated: it answers only when the store's own
 * {@code allow-raw-reveal} setting permits it <em>and</em> the live exposure policy allows values. A raw
 * agent event can contain the contents of the developer's source files, so it is never revealed by default.
 */
public abstract class AbstractAgentSessionController {

    /** The event-page size used when a caller does not ask for one, matching the other adapters. */
    private static final int DEFAULT_EVENT_LIMIT = 200;

    private final AgentSessionStore store;
    private final MicronautExposurePolicy exposure;

    protected AbstractAgentSessionController(AgentSessionStore store, MicronautExposurePolicy exposure) {
        this.store = store;
        this.exposure = exposure;
    }

    @Get("/sessions")
    @Produces(MediaType.APPLICATION_JSON)
    public CopilotSessionListDto sessions(@QueryValue @Nullable Long since, @QueryValue @Nullable Long until) {
        return store.listSessions(since, until);
    }

    @Get("/dashboard")
    @Produces(MediaType.APPLICATION_JSON)
    public CopilotDashboardDto dashboard() {
        return store.dashboard();
    }

    @Get("/sessions/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<CopilotSessionDetail> session(@PathVariable String id) {
        CopilotSessionDetail detail = store.getSession(id);
        return detail == null ? HttpResponse.notFound() : HttpResponse.ok(detail);
    }

    @Get("/sessions/{id}/events")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<CopilotEventListDto> events(
            @PathVariable String id,
            @QueryValue @Nullable String category,
            @QueryValue @Nullable Long since,
            @QueryValue @Nullable Integer limit) {
        if (store.getSession(id) == null) {
            return HttpResponse.notFound();
        }
        int effectiveLimit = limit == null ? DEFAULT_EVENT_LIMIT : limit;
        var events = store.listEvents(id, category, since, effectiveLimit);
        int total = store.totalEvents(id, category, since);
        return HttpResponse.ok(new CopilotEventListDto(id, total, events.size(), events));
    }

    @Get("/sessions/{id}/events/{eventId}/raw")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<CopilotRawEventDto> raw(@PathVariable String id, @PathVariable String eventId) {
        if (!store.isRawRevealAllowed() || exposure.valueExposure() == ValueExposure.METADATA_ONLY) {
            return HttpResponse.notFound();
        }
        String json = store.getRawEventJson(id, eventId);
        return json == null ? HttpResponse.notFound() : HttpResponse.ok(new CopilotRawEventDto(id, eventId, json));
    }
}
