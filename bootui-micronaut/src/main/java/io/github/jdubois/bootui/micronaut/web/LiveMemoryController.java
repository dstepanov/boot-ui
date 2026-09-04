package io.github.jdubois.bootui.micronaut.web;

import io.github.jdubois.bootui.core.dto.LiveMemoryReport;
import io.github.jdubois.bootui.engine.memory.MemoryReportProvider;
import io.github.jdubois.bootui.micronaut.RequiresBootUi;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;

/**
 * Controller for the Live Memory panel ({@code GET /bootui/api/live-memory}).
 *
 * <p>Passive: it reads JMX memory/GC state and runs the shared sizing model over the caller-supplied
 * container assumptions. It shares its provider with {@link JvmTuningController}, which renders the same
 * model with the tuning-oriented framing.
 */
@RequiresBootUi
@Controller(BootUiApiPaths.API + "/live-memory")
public class LiveMemoryController {

    private final MemoryReportProvider provider;

    public LiveMemoryController(MemoryReportProvider provider) {
        this.provider = provider;
    }

    @Get
    @Produces(MediaType.APPLICATION_JSON)
    public LiveMemoryReport memory(
            @QueryValue @Nullable Long totalMemoryMb,
            @QueryValue @Nullable Integer threadCount,
            @QueryValue @Nullable Integer headRoomPercent,
            @QueryValue @Nullable Boolean kubernetesBurstableEnabled,
            @QueryValue @Nullable Boolean kubernetesActuatorEnabled) {
        return provider.buildReport(
                totalMemoryMb, threadCount, headRoomPercent, kubernetesBurstableEnabled, kubernetesActuatorEnabled);
    }
}
