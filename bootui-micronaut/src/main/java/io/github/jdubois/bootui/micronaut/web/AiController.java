package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.AiChatDetailDto;
import io.github.jdubois.bootui.core.dto.AiChatSummaryDto;
import io.github.jdubois.bootui.core.dto.AiOverviewDto;
import io.github.jdubois.bootui.core.dto.AiTokenSeriesDto;
import io.github.jdubois.bootui.engine.telemetry.AiUsageService;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import java.util.List;

/**
 * Controller for the AI Framework panel ({@code /bootui/api/ai}).
 *
 * <p>A thin transport adapter over the shared engine {@link AiUsageService}, which derives model calls,
 * token usage and tool invocations from the OpenTelemetry GenAI semantic conventions in the captured spans.
 * Nothing is captured that the application's own instrumentation did not already record.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/ai")
public class AiController {

    private final AiUsageService service;

    public AiController(AiUsageService service) {
        this.service = service;
    }

    @Get("/overview")
    @Produces(MediaType.APPLICATION_JSON)
    public AiOverviewDto overview() {
        return service.overview();
    }

    @Get("/chats")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AiChatSummaryDto> chats(@QueryValue(defaultValue = "100") int limit) {
        return service.chats(limit);
    }

    @Get("/chats/{spanId}")
    @Produces(MediaType.APPLICATION_JSON)
    public HttpResponse<AiChatDetailDto> chatDetail(@PathVariable String spanId) {
        return service.chatDetail(spanId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Get("/tokens")
    @Produces(MediaType.APPLICATION_JSON)
    public AiTokenSeriesDto tokens(@QueryValue @Nullable Integer minutes) {
        return service.tokens(minutes);
    }
}
